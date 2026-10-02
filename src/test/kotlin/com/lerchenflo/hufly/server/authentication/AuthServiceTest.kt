package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.core.security.TokenCipher
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import com.lerchenflo.hufly.server.authentication.model.DeviceType
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
    private val tokenCipher = TokenCipher("test-secret-that-is-long-enough-for-hs256-signing")
    private val authService = AuthService(userRepository, refreshTokenRepository, jwtService, hashEncoder, tokenCipher, clock)

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

    // Replay recovery like SchneaggchatV3server: the old token keeps working until the client uses the new one.

    @Test
    fun `retry with the old token returns the same refresh token as the lost response, even much later`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val lost = authService.refresh(login.refreshToken)
        clock.advance(Duration.ofDays(3))

        val retried = authService.refresh(login.refreshToken)

        assertEquals(lost.refreshToken, retried.refreshToken)
        assertEquals(anna.id, jwtService.userIdFromAccessToken(retried.accessToken))
    }

    @Test
    fun `retries do not rotate, the delivered token keeps working`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val lost = authService.refresh(login.refreshToken)
        authService.refresh(login.refreshToken)
        authService.refresh(login.refreshToken)

        authService.refresh(lost.refreshToken)
    }

    @Test
    fun `using the new token retires the old one`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val second = authService.refresh(login.refreshToken)
        authService.refresh(second.refreshToken)

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `retry after logout is unauthorized, also when logging out with the old token`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        authService.logout(login.refreshToken)

        assertUnauthorized { authService.refresh(login.refreshToken) }
        assertTrue(refreshTokenRepository.tokens.isEmpty())
    }

    @Test
    fun `retry of a deleted user is unauthorized`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        authService.refresh(login.refreshToken)
        userRepository.save(anna.copy(deleted = true))

        assertUnauthorized { authService.refresh(login.refreshToken) }
    }

    @Test
    fun `rotation keeps one row per session and stores no raw token`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        val current = authService.refresh(login.refreshToken)

        val row = refreshTokenRepository.tokens.single()
        assertFalse(row.toString().contains(current.refreshToken))
        assertFalse(row.toString().contains(login.refreshToken))
    }

    @Test
    fun `rotation slides the session expiry`() {
        val login = authService.login("anna@hufly.test", "Secret123")
        clock.advance(Duration.ofDays(10))

        authService.refresh(login.refreshToken)

        assertEquals(clock.instant().plus(jwtService.refreshTokenValidity), refreshTokenRepository.tokens.single().expiresAt)
    }

    // Sessions per device (USR-5)

    private val pixel = AuthService.Device("Pixel 7", DeviceType.ANDROID)
    private val ipad = AuthService.Device("iPad", DeviceType.IOS)

    @Test
    fun `login stores the device on the session and puts the session id into the access token`() {
        val tokens = authService.login("anna@hufly.test", "Secret123", pixel)

        val session = refreshTokenRepository.tokens.single()
        assertEquals("Pixel 7", session.deviceName)
        assertEquals(DeviceType.ANDROID, session.deviceType)
        assertEquals(session.id, jwtService.sessionIdFromAccessToken(tokens.accessToken))
    }

    @Test
    fun `logging in again on the same device replaces its session`() {
        val first = authService.login("anna@hufly.test", "Secret123", pixel)
        authService.login("anna@hufly.test", "Secret123", ipad)

        authService.login("anna@hufly.test", "Secret123", pixel)

        assertEquals(2, refreshTokenRepository.tokens.size)
        assertUnauthorized { authService.refresh(first.refreshToken) }
    }

    @Test
    fun `refresh keeps the session id and records the last use`() {
        val login = authService.login("anna@hufly.test", "Secret123", pixel)
        clock.advance(Duration.ofHours(2))

        val refreshed = authService.refresh(login.refreshToken)

        val session = refreshTokenRepository.tokens.single()
        assertEquals(session.id, jwtService.sessionIdFromAccessToken(refreshed.accessToken))
        assertEquals(clock.instant(), session.lastUsedAt)
    }

    @Test
    fun `sessions list the own devices and mark the current one`() {
        val phone = authService.login("anna@hufly.test", "Secret123", pixel)
        authService.login("anna@hufly.test", "Secret123", ipad)
        val current = jwtService.sessionIdFromAccessToken(phone.accessToken)

        val sessions = authService.sessions(anna.id, current)

        assertEquals(setOf("Pixel 7", "iPad"), sessions.map { it.deviceName }.toSet())
        assertEquals("Pixel 7", sessions.single { it.current }.deviceName)
    }

    @Test
    fun `ending one session logs out only that device`() {
        authService.login("anna@hufly.test", "Secret123", pixel)
        val tablet = authService.login("anna@hufly.test", "Secret123", ipad)
        val tabletSession = jwtService.sessionIdFromAccessToken(tablet.accessToken)!!

        authService.endSession(anna.id, tabletSession)

        assertUnauthorized { authService.refresh(tablet.refreshToken) }
        assertEquals(listOf("Pixel 7"), refreshTokenRepository.tokens.map { it.deviceName })
    }

    @Test
    fun `sessions of other users cannot be ended`() {
        val bob = userRepository.save(testUser(email = "bob@hufly.test", hashedPassword = hashEncoder.encode("Secret123")))
        val bobTokens = authService.login("bob@hufly.test", "Secret123", pixel)

        val error = assertFailsWith<ResponseStatusException> {
            authService.endSession(anna.id, jwtService.sessionIdFromAccessToken(bobTokens.accessToken)!!)
        }

        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        authService.refresh(bobTokens.refreshToken)
        assertEquals(bob.id, refreshTokenRepository.tokens.single().userId)
    }

    @Test
    fun `logout everywhere ends all own sessions only`() {
        val phone = authService.login("anna@hufly.test", "Secret123", pixel)
        userRepository.save(testUser(email = "bob@hufly.test", hashedPassword = hashEncoder.encode("Secret123")))
        val bob = authService.login("bob@hufly.test", "Secret123", pixel)

        authService.logoutEverywhere(anna.id)

        assertUnauthorized { authService.refresh(phone.refreshToken) }
        authService.refresh(bob.refreshToken)
    }
}
