package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.authentication.model.UNKNOWN_DEVICE_NAME
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.core.security.TokenCipher
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

fun normalizeEmail(email: String): String = email.trim().lowercase()

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val hashEncoder: HashEncoder,
    private val tokenCipher: TokenCipher,
    private val clock: Clock,
) {
    data class TokenPair(val accessToken: String, val refreshToken: String)

    data class Device(val name: String, val type: DeviceType, val id: String? = null) {
        companion object {
            val UNKNOWN = Device(UNKNOWN_DEVICE_NAME, DeviceType.OTHER)
        }
    }

    data class SessionResponse(
        val id: String,
        val deviceName: String,
        val deviceType: DeviceType,
        /** Epoch milliseconds. */
        val createdAt: Long,
        val lastUsedAt: Long?,
        val current: Boolean,
    )

    /** Checked against for unknown emails so the response time does not reveal which emails exist. */
    private val dummyHash by lazy { hashEncoder.encode("unknown-user") }

    /** Logging in again on the same device replaces that device's session, see [RefreshToken.deviceName]. */
    fun login(email: String, password: String, device: Device = Device.UNKNOWN): TokenPair {
        val user = userRepository.findByEmail(normalizeEmail(email))
        val passwordMatches = hashEncoder.matches(password, user?.hashedPassword ?: dummyHash)
        if (user == null || !passwordMatches || user.deleted) {
            throw unauthorized("Invalid email or password")
        }
        if (device.id != null) {
            refreshTokenRepository.deleteByUserIdAndDeviceId(user.id, device.id)
        } else {
            refreshTokenRepository.deleteByUserIdAndDeviceNameAndDeviceTypeAndDeviceIdIsNull(user.id, device.name, device.type)
        }
        return issueTokens(user.id, device)
    }

    /**
     * Rotates the session in place. A client that lost the response retries with its old token and gets the same
     * new token back, until it uses the new token once (like SchneaggchatV3server).
     */
    fun refresh(refreshToken: String): TokenPair {
        val userId = jwtService.userIdFromRefreshToken(refreshToken)
            ?: throw unauthorized("Invalid refresh token")
        if (userRepository.findById(userId)?.deleted != false) throw unauthorized("Invalid refresh token")
        val hash = hashEncoder.sha256(refreshToken)

        val newRefreshToken = jwtService.generateRefreshToken(userId)
        val newHash = hashEncoder.sha256(newRefreshToken)
        val now = clock.instant()
        val rotated = refreshTokenRepository.rotate(
            hash, newHash, tokenCipher.encrypt(newRefreshToken), now.plus(jwtService.refreshTokenValidity), now,
        )
        if (rotated == 1L) {
            val session = refreshTokenRepository.findByHashedToken(newHash) ?: throw unauthorized("Invalid refresh token")
            return TokenPair(jwtService.generateAccessToken(userId, session.id), newRefreshToken)
        }

        val session = refreshTokenRepository.findByPreviousHashedToken(hash)
            ?.takeIf { it.userId == userId && it.encryptedToken != null }
            ?: throw unauthorized("Invalid refresh token")
        return TokenPair(jwtService.generateAccessToken(userId, session.id), tokenCipher.decrypt(session.encryptedToken!!))
    }

    /** Also works with the previous token of a client that lost its last refresh response. */
    fun logout(refreshToken: String) {
        val hash = hashEncoder.sha256(refreshToken)
        refreshTokenRepository.deleteByHashedTokenOrPreviousHashedToken(hash, hash)
    }

    fun sessions(userId: ObjectId, currentSessionId: ObjectId?): List<SessionResponse> =
        refreshTokenRepository.findByUserId(userId).sortedByDescending { it.lastUsedAt ?: it.createdAt }.map {
            SessionResponse(
                id = it.id.toHexString(),
                deviceName = it.deviceName,
                deviceType = it.deviceType,
                createdAt = it.createdAt.toEpochMilli(),
                lastUsedAt = it.lastUsedAt?.toEpochMilli(),
                current = it.id == currentSessionId,
            )
        }

    /** Its access tokens stay valid until they expire (at most 15 minutes). */
    fun endSession(userId: ObjectId, sessionId: ObjectId) {
        val session = refreshTokenRepository.findById(sessionId)?.takeIf { it.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")
        refreshTokenRepository.deleteById(session.id)
    }

    fun logoutEverywhere(userId: ObjectId) {
        refreshTokenRepository.deleteByUserId(userId)
    }

    private fun issueTokens(userId: ObjectId, device: Device): TokenPair {
        val refreshToken = jwtService.generateRefreshToken(userId)
        val now = clock.instant()
        val session = refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashEncoder.sha256(refreshToken),
                expiresAt = now.plus(jwtService.refreshTokenValidity),
                createdAt = now,
                deviceName = device.name,
                deviceType = device.type,
                deviceId = device.id,
            )
        )
        return TokenPair(jwtService.generateAccessToken(userId, session.id), refreshToken)
    }

    private fun unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)
}
