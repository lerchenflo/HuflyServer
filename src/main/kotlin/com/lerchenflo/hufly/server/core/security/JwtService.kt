package com.lerchenflo.hufly.server.core.security

import io.jsonwebtoken.Claims
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

    /** [sessionId] identifies the device session, so the server knows which one a request comes from. */
    fun generateAccessToken(userId: ObjectId, sessionId: ObjectId? = null): String = generate(userId, TokenType.ACCESS, sessionId)

    fun generateRefreshToken(userId: ObjectId): String = generate(userId, TokenType.REFRESH)

    fun userIdFromAccessToken(token: String): ObjectId? = userIdFrom(token, TokenType.ACCESS)

    fun userIdFromRefreshToken(token: String): ObjectId? = userIdFrom(token, TokenType.REFRESH)

    fun sessionIdFromAccessToken(token: String): ObjectId? =
        claimsOf(token, TokenType.ACCESS)?.get("sid", String::class.java)?.takeIf(ObjectId::isValid)?.let(::ObjectId)

    private fun generate(userId: ObjectId, type: TokenType, sessionId: ObjectId? = null): String {
        val now = clock.instant()
        return Jwts.builder()
            .subject(userId.toHexString())
            .id(UUID.randomUUID().toString())
            .claim("type", type.claim)
            .apply { if (sessionId != null) claim("sid", sessionId.toHexString()) }
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(type.validity)))
            .signWith(secretKey, Jwts.SIG.HS256)
            .compact()
    }

    private fun userIdFrom(token: String, type: TokenType): ObjectId? =
        claimsOf(token, type)?.subject?.takeIf(ObjectId::isValid)?.let(::ObjectId)

    private fun claimsOf(token: String, type: TokenType): Claims? {
        val claims = try {
            parser.parseSignedClaims(token).payload
        } catch (_: JwtException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return claims.takeIf { it["type"] == type.claim }
    }
}
