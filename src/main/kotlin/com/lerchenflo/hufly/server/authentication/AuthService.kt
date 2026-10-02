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

fun normalizeEmail(email: String): String = email.trim().lowercase()

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

    fun refresh(refreshToken: String): TokenPair {
        val userId = jwtService.userIdFromRefreshToken(refreshToken)
            ?: throw unauthorized("Invalid refresh token")
        val deleted = refreshTokenRepository.deleteByHashedToken(hashEncoder.sha256(refreshToken))
        if (deleted == 0L) throw unauthorized("Invalid refresh token")
        if (userRepository.findById(userId)?.deleted != false) throw unauthorized("User no longer exists")
        return issueTokens(userId)
    }

    fun logout(refreshToken: String) {
        refreshTokenRepository.deleteByHashedToken(hashEncoder.sha256(refreshToken))
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
