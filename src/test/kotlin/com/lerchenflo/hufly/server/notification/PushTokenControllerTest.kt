package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The app registers the push token of its install on its own session. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class PushTokenControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var sessions: FakeRefreshTokenRepository

    private val anna = testUser()
    private lateinit var session: RefreshToken

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        sessions.tokens.clear()
        userRepository.save(anna)
        stableRepository.save(testStable(adminUserId = anna.id))
        session = sessions.save(RefreshToken(userId = anna.id, hashedToken = "h", expiresAt = Long.MAX_VALUE, createdAt = 0L))
    }

    private fun call(method: HttpMethod, body: String? = null, sessionId: ObjectId? = session.id) =
        mockMvc.request(method, "/users/me/pushtoken") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(anna.id, sessionId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `register and remove the session's token`() {
        call(HttpMethod.PUT, """{"platform":"IOS","token":"apns-abc"}""").andExpect { status { isNoContent() } }
        assertEquals("apns-abc" to PushPlatform.IOS, sessions.findById(session.id)!!.let { it.pushToken to it.pushPlatform })

        call(HttpMethod.DELETE).andExpect { status { isNoContent() } }
        assertNull(sessions.findById(session.id)!!.pushToken)
    }

    @Test
    fun `invalid bodies and tokens without a session answer 400`() {
        listOf("""{"platform":"WEB","token":"x"}""", """{"platform":"IOS","token":""}""", """{"platform":"IOS","token":"${"x".repeat(4097)}"}""")
            .forEach { call(HttpMethod.PUT, it).andExpect { status { isBadRequest() } } }
        call(HttpMethod.PUT, """{"platform":"IOS","token":"x"}""", sessionId = null).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `needs a token`() {
        mockMvc.request(HttpMethod.DELETE, "/users/me/pushtoken").andExpect { status { isUnauthorized() } }
    }
}
