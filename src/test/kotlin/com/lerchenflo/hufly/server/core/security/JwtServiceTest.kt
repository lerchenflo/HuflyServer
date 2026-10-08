package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.minutes
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class JwtServiceTest {

    private val secret = "test-secret-that-is-long-enough-for-hs256-signing"
    private val clock = MutableClock()
    private val jwtService = JwtService(secret, clock)
    private val userId = ObjectId("66f000000000000000000001")

    @Test
    fun `access token resolves to its user`() {
        val token = jwtService.generateAccessToken(userId)

        assertEquals(userId, jwtService.userIdFromAccessToken(token))
    }

    @Test
    fun `refresh token resolves to its user`() {
        val token = jwtService.generateRefreshToken(userId)

        assertEquals(userId, jwtService.userIdFromRefreshToken(token))
    }

    @Test
    fun `refresh token is not accepted as access token`() {
        val token = jwtService.generateRefreshToken(userId)

        assertNull(jwtService.userIdFromAccessToken(token))
    }

    @Test
    fun `access token is not accepted as refresh token`() {
        val token = jwtService.generateAccessToken(userId)

        assertNull(jwtService.userIdFromRefreshToken(token))
    }

    @Test
    fun `token signed with another secret is rejected`() {
        val foreign = JwtService("another-secret-that-is-long-enough-for-hs256", clock)
        val token = foreign.generateAccessToken(userId)

        assertNull(jwtService.userIdFromAccessToken(token))
    }

    @Test
    fun `access token expires after 15 minutes`() {
        val token = jwtService.generateAccessToken(userId)

        clock.advance(minutes(14))
        assertEquals(userId, jwtService.userIdFromAccessToken(token))

        clock.advance(minutes(2))
        assertNull(jwtService.userIdFromAccessToken(token))
    }

    @Test
    fun `refresh token expires after 30 days`() {
        val token = jwtService.generateRefreshToken(userId)

        clock.advance(days(29))
        assertEquals(userId, jwtService.userIdFromRefreshToken(token))

        clock.advance(days(2))
        assertNull(jwtService.userIdFromRefreshToken(token))
    }

    @Test
    fun `garbage token is rejected`() {
        assertNull(jwtService.userIdFromAccessToken("not-a-jwt"))
    }

    @Test
    fun `two refresh tokens issued in the same instant differ`() {
        assertNotEquals(jwtService.generateRefreshToken(userId), jwtService.generateRefreshToken(userId))
    }

    @Test
    fun `access token carries the session id`() {
        val sessionId = ObjectId("66f0000000000000000000f1")

        val token = jwtService.generateAccessToken(userId, sessionId)

        assertEquals(sessionId, jwtService.sessionIdFromAccessToken(token))
        assertEquals(userId, jwtService.userIdFromAccessToken(token))
    }

    @Test
    fun `access token without session id has none`() {
        assertNull(jwtService.sessionIdFromAccessToken(jwtService.generateAccessToken(userId)))
    }
}
