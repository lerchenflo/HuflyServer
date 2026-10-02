package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import com.lerchenflo.hufly.server.core.security.CountingHashEncoder
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthServiceTest {

    private val clock = MutableClock()
    private val jwtService = JwtService("test-secret-that-is-long-enough-for-hs256-signing", clock)
    private val hashEncoder = CountingHashEncoder()
    private val userRepository = FakeUserRepository()
    private val refreshTokenRepository = FakeRefreshTokenRepository()
    private val authService = AuthService(userRepository, refreshTokenRepository, jwtService, hashEncoder, clock)

    private val anna = testUser(
        id = ObjectId("66f000000000000000000001"),
        email = "anna@hufly.test",
        hashedPassword = hashEncoder.encode("Secret123"),
    )

    @BeforeTest
    fun setUp() {
        userRepository.save(anna)
    }

    private fun assertUnauthorized(block: () -> Unit) {
        val error = assertFailsWith<ResponseStatusException> { block() }
        assertEquals(HttpStatus.UNAUTHORIZED, error.statusCode)
    }

    @Test
    fun `login with correct credentials issues tokens for that user`() {
        val tokens = authService.login("anna@hufly.test", "Secret123")

        assertEquals(anna.id, jwtService.userIdFromAccessToken(tokens.accessToken))
        assertEquals(anna.id, jwtService.userIdFromRefreshToken(tokens.refreshToken))
    }

    @Test
    fun `login ignores email case and surrounding whitespace`() {
        val tokens = authService.login("  Anna@Hufly.test ", "Secret123")

        assertEquals(anna.id, jwtService.userIdFromAccessToken(tokens.accessToken))
    }

    @Test
    fun `login with wrong password is unauthorized`() {
        assertUnauthorized { authService.login("anna@hufly.test", "wrong") }
    }

    @Test
    fun `login with unknown email is unauthorized`() {
        assertUnauthorized { authService.login("bob@hufly.test", "Secret123") }
    }

    @Test
    fun `login with unknown email still runs a password check so timing does not reveal registered emails`() {
        assertUnauthorized { authService.login("bob@hufly.test", "Secret123") }

        assertEquals(1, hashEncoder.bcryptChecks)
    }

    @Test
    fun `refresh of a user deleted after login is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        userRepository.save(anna.copy(deleted = true))

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `login of a deleted user is unauthorized`() {
        userRepository.save(anna.copy(deleted = true))

        assertUnauthorized { authService.login("anna@hufly.test", "Secret123") }
    }

    @Test
    fun `login stores only a hash of the refresh token`() {
        val tokens = authService.login("anna@hufly.test", "Secret123")

        val stored = refreshTokenRepository.tokens.single()
        assertEquals(anna.id, stored.userId)
        assertFalse(stored.hashedToken == tokens.refreshToken)
    }

    @Test
    fun `refresh returns new tokens for the same user`() {
        val login = authService.login("anna@hufly.test", "Secret123")

        val refreshed = authService.refresh(login.refreshToken)

        assertEquals(anna.id, jwtService.userIdFromAccessToken(refreshed.accessToken))
        assertEquals(anna.id, jwtService.userIdFromRefreshToken(refreshed.refreshToken))
    }

    @Test
    fun `rotated refresh token keeps working`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val refreshed = authService.refresh(login.refreshToken)

        authService.refresh(refreshed.refreshToken)
    }

    @Test
    fun `refresh with a validly signed but never issued token is unauthorized`() {
        val forged = jwtService.generateRefreshToken(anna.id)

        assertUnauthorized { authService.refresh(forged) }
    }

    @Test
    fun `refresh with an access token is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")

        assertUnauthorized { authService.refresh(login.accessToken) }
    }

    @Test
    fun `logout ends the session of that refresh token`() {
        val login = authService.login("anna@hufly.test", "Secret123")

        authService.logout(login.refreshToken)

        assertTrue(refreshTokenRepository.tokens.isEmpty())
        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `logout keeps other sessions of the same user`() {
        val phone = authService.login("anna@hufly.test", "Secret123")
        val tablet = authService.login("anna@hufly.test", "Secret123")

        authService.logout(phone.refreshToken)

        authService.refresh(tablet.refreshToken)
    }

    @Test
    fun `logout with an unknown token succeeds`() {
        authService.logout("unknown")
    }

    // Replay grace: a client that lost the refresh response retries with the token it still has.

    @Test
    fun `retry with the rotated token within 30 seconds gets working tokens`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        clock.advance(Duration.ofSeconds(29))

        val retried = authService.refresh(login.refreshToken)

        authService.refresh(retried.refreshToken)
    }

    @Test
    fun `retry replaces the token from the lost response`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val lost = authService.refresh(login.refreshToken)

        authService.refresh(login.refreshToken)

        assertUnauthorized { authService.refresh(lost.refreshToken) }
    }

    @Test
    fun `retry after 30 seconds is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        clock.advance(Duration.ofSeconds(31))

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `repeated retries do not extend the 30 seconds`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        clock.advance(Duration.ofSeconds(20))
        authService.refresh(login.refreshToken)
        clock.advance(Duration.ofSeconds(20))

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `retry after logout is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val current = authService.refresh(login.refreshToken)
        authService.logout(current.refreshToken)

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `retry of a deleted user is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        userRepository.save(anna.copy(deleted = true))

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `the token before the previous one never works`() {
        val first = authService.login("anna@hufly.test", "Secret123")
        val second = authService.refresh(first.refreshToken)
        authService.refresh(second.refreshToken)

        assertUnauthorized { authService.refresh(first.refreshToken) }
    }
}
