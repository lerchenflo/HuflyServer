package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant

fun normalizeEmail(email: String): String = email.trim().lowercase()

private val REPLAY_GRACE: Duration = Duration.ofSeconds(30)

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val jwtService: JwtService,
    private val hashEncoder: HashEncoder,
    private val clock: Clock,
) {
    data class TokenPair(val accessToken: String, val refreshToken: String)

    /** Checked against for unknown emails so the response time does not reveal which emails exist. */
    private val dummyHash by lazy { hashEncoder.encode("unknown-user") }

    fun login(email: String, password: String): TokenPair {
        val user = userRepository.findByEmail(normalizeEmail(email))
        val passwordMatches = hashEncoder.matches(password, user?.hashedPassword ?: dummyHash)
        if (user == null || !passwordMatches || user.deleted) {
            throw unauthorized("Invalid email or password")
        }
        return issueTokens(user.id)
    }

    /**
     * Rotates the session: the presented token stops working and a new pair is issued. A client that lost the
     * response may retry with the same token for [REPLAY_GRACE]; the retry replaces the token it never received.
     */
    fun refresh(refreshToken: String): TokenPair {
        val userId = jwtService.userIdFromRefreshToken(refreshToken)
            ?: throw unauthorized("Invalid refresh token")
        val hash = hashEncoder.sha256(refreshToken)
        val rotatedFrom = if (refreshTokenRepository.deleteByHashedToken(hash) > 0) {
            RotatedFrom(hash, clock.instant())
        } else {
            replayedSession(hash) ?: throw unauthorized("Invalid refresh token")
        }
        if (userRepository.findById(userId)?.deleted != false) throw unauthorized("User no longer exists")
        return issueTokens(userId, rotatedFrom)
    }

    private data class RotatedFrom(val previousHashedToken: String, val rotatedAt: Instant)

    /** The session that was rotated away from [hash] within the grace period, now rotated once more. */
    private fun replayedSession(hash: String): RotatedFrom? {
        val session = refreshTokenRepository.findByPreviousHashedToken(hash) ?: return null
        val rotatedAt = session.rotatedAt ?: return null
        if (clock.instant().isAfter(rotatedAt.plus(REPLAY_GRACE))) return null
        if (refreshTokenRepository.deleteByHashedToken(session.hashedToken) == 0L) return null
        return RotatedFrom(hash, rotatedAt)
    }

    fun logout(refreshToken: String) {
        refreshTokenRepository.deleteByHashedToken(hashEncoder.sha256(refreshToken))
    }

    private fun issueTokens(userId: ObjectId, rotatedFrom: RotatedFrom? = null): TokenPair {
        val refreshToken = jwtService.generateRefreshToken(userId)
        val now = clock.instant()
        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashEncoder.sha256(refreshToken),
                previousHashedToken = rotatedFrom?.previousHashedToken,
                rotatedAt = rotatedFrom?.rotatedAt,
                expiresAt = now.plus(jwtService.refreshTokenValidity),
                createdAt = now,
            )
        )
        return TokenPair(jwtService.generateAccessToken(userId), refreshToken)
    }

    private fun unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)
}
