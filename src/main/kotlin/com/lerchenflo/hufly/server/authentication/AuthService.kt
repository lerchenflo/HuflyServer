package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.authentication.model.UNKNOWN_DEVICE_NAME
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.core.security.TokenCipher
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

fun normalizeEmail(email: String): String = email.trim().lowercase()

/** Erased accounts get `deleted-<id>@deleted.invalid`; nobody may take such an address, or that erase would fail. */
fun requireUsableEmail(normalizedEmail: String) {
    if (normalizedEmail.endsWith("@$ERASED_EMAIL_DOMAIN")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Email not allowed")
}

fun erasedEmail(id: ObjectId) = "deleted-${id.toHexString()}@$ERASED_EMAIL_DOMAIN"

private const val ERASED_EMAIL_DOMAIN = "deleted.invalid"

@Service
class AuthService(
    private val accountRepository: AccountRepository,
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
        val account = accountRepository.findByEmail(normalizeEmail(email))
        val passwordMatches = hashEncoder.matches(password, account?.hashedPassword ?: dummyHash)
        if (account == null || !passwordMatches || account.deleted) {
            throw unauthorized("Invalid email or password")
        }
        if (device.id != null) {
            refreshTokenRepository.deleteByUserIdAndDeviceId(account.id, device.id)
        } else {
            refreshTokenRepository.deleteByUserIdAndDeviceNameAndDeviceTypeAndDeviceIdIsNull(account.id, device.name, device.type)
        }
        return issueTokens(account.id, device)
    }

    /** Self-signup: a login without a stable; the app then joins one with an invite code or creates one. */
    fun register(email: String, password: String, displayName: String, device: Device = Device.UNKNOWN): TokenPair {
        val normalized = normalizeEmail(email)
        requireUsableEmail(normalized)
        if (accountRepository.findByEmail(normalized) != null) throw emailInUse()
        val now = clock.millis()
        val account = try {
            accountRepository.insert(
                Account(
                    email = normalized,
                    displayName = displayName.trim(),
                    hashedPassword = hashEncoder.encode(password),
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } catch (e: DuplicateKeyException) {
            throw emailInUse()
        }
        return issueTokens(account.id, device)
    }

    /**
     * Rotates the session in place. A client that lost the response retries with its old token and gets the same
     * new token back, until it uses the new token once (like SchneaggchatV3server).
     */
    fun refresh(refreshToken: String): TokenPair {
        val userId = jwtService.userIdFromRefreshToken(refreshToken)
            ?: throw unauthorized("Invalid refresh token")
        if (accountRepository.findById(userId)?.deleted != false) throw unauthorized("Invalid refresh token")
        val hash = hashEncoder.sha256(refreshToken)

        val newRefreshToken = jwtService.generateRefreshToken(userId)
        val newHash = hashEncoder.sha256(newRefreshToken)
        val now = clock.millis()
        val rotated = refreshTokenRepository.rotate(
            hash, newHash, tokenCipher.encrypt(newRefreshToken), now + jwtService.refreshTokenValidity, now,
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
                createdAt = it.createdAt,
                lastUsedAt = it.lastUsedAt,
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
        val now = clock.millis()
        val session = refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashEncoder.sha256(refreshToken),
                expiresAt = now + jwtService.refreshTokenValidity,
                createdAt = now,
                deviceName = device.name,
                deviceType = device.type,
                deviceId = device.id,
            )
        )
        return TokenPair(jwtService.generateAccessToken(userId, session.id), refreshToken)
    }

    private fun unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)

    private fun emailInUse() = CodedException(HttpStatus.CONFLICT, EMAIL_IN_USE, "Email already in use")

    companion object {
        const val EMAIL_IN_USE = "EMAIL_IN_USE"
    }
}
