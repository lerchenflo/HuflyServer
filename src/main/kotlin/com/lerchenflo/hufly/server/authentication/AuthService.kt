package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
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
     * Rotates the session in place. A client that lost the response retries with its old token and gets the same
     * new token back, until it uses the new token once (like SchneaggchatV3server).
     */
    fun refresh(refreshToken: String): TokenPair {
        val userId = jwtService.userIdFromRefreshToken(refreshToken)
            ?: throw unauthorized("Invalid refresh token")
        if (userRepository.findById(userId)?.deleted != false) throw unauthorized("Invalid refresh token")
        val hash = hashEncoder.sha256(refreshToken)

        val newRefreshToken = jwtService.generateRefreshToken(userId)
        val expiresAt = clock.instant().plus(jwtService.refreshTokenValidity)
        if (refreshTokenRepository.rotate(hash, hashEncoder.sha256(newRefreshToken), tokenCipher.encrypt(newRefreshToken), expiresAt) == 1L) {
            return TokenPair(jwtService.generateAccessToken(userId), newRefreshToken)
        }

        val session = refreshTokenRepository.findByPreviousHashedToken(hash)
            ?.takeIf { it.userId == userId && it.encryptedToken != null }
            ?: throw unauthorized("Invalid refresh token")
        return TokenPair(jwtService.generateAccessToken(userId), tokenCipher.decrypt(session.encryptedToken!!))
    }

    /** Also works with the previous token of a client that lost its last refresh response. */
    fun logout(refreshToken: String) {
        val hash = hashEncoder.sha256(refreshToken)
        refreshTokenRepository.deleteByHashedTokenOrPreviousHashedToken(hash, hash)
    }

    private fun issueTokens(userId: ObjectId): TokenPair {
        val refreshToken = jwtService.generateRefreshToken(userId)
        val now = clock.instant()
        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                hashedToken = hashEncoder.sha256(refreshToken),
                expiresAt = now.plus(jwtService.refreshTokenValidity),
                createdAt = now,
            )
        )
        return TokenPair(jwtService.generateAccessToken(userId), refreshToken)
    }

    private fun unauthorized(reason: String) = ResponseStatusException(HttpStatus.UNAUTHORIZED, reason)
}
