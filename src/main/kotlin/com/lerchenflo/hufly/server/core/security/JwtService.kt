package com.lerchenflo.hufly.server.core.security

import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID

@Service
class JwtService(
    @Value($$"${jwt.secret}") jwtSecret: String,
    private val clock: Clock,
) {
    private enum class TokenType(val claim: String, val validity: Duration) {
        ACCESS("access_token", Duration.ofMinutes(15)),
        REFRESH("refresh_token", Duration.ofDays(30)),
    }

    private val secretKey = Keys.hmacShaKeyFor(jwtSecret.toByteArray())

    private val parser = Jwts.parser()
        .verifyWith(secretKey)
        .clock { Date.from(clock.instant()) }
        .build()

    val refreshTokenValidity: Duration = TokenType.REFRESH.validity

    fun generateAccessToken(userId: ObjectId): String = generate(userId, TokenType.ACCESS)

    fun generateRefreshToken(userId: ObjectId): String = generate(userId, TokenType.REFRESH)

    fun userIdFromAccessToken(token: String): ObjectId? = userIdFrom(token, TokenType.ACCESS)

    fun userIdFromRefreshToken(token: String): ObjectId? = userIdFrom(token, TokenType.REFRESH)

    private fun generate(userId: ObjectId, type: TokenType): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(userId.toHexString())
            .id(UUID.randomUUID().toString())
            .claim("type", type.claim)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(type.validity)))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    private fun userIdFrom(token: String, type: TokenType): ObjectId? {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (_: JwtException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (claims["type"] != type.claim) return null
        return claims.subject?.takeIf(ObjectId::isValid)?.let(::ObjectId)
    }
}
