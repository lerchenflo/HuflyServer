package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.LogCapture
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Failed requests are logged with what is needed to find their cause, never with request bodies or rejected values. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class FailedRequestLogTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    private val admin = testUser()
    private val rider = testUser()
    private lateinit var logs: LogCapture

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        listOf(admin, rider).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        logs = LogCapture(FailedRequestLog::class.java)
    }

    @AfterTest
    fun tearDown() = logs.close()

    private fun call(method: HttpMethod, path: String, body: String? = null, as_: org.bson.types.ObjectId? = null) =
        mockMvc.request(method, path) {
            header("User-Agent", "Ktor client")
            if (as_ != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(as_)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `a method the route does not take is logged with path, cause, allowed methods, account and app`() {
        call(HttpMethod.POST, "/events/sync", as_ = rider.id).andExpect { status { isMethodNotAllowed() } }
        call(HttpMethod.POST, "/").andExpect { status { isMethodNotAllowed() } }

        assertEquals(
            listOf(
                // Also matches PUT/DELETE /events/{eventId}.
                "INFO 405 POST /events/sync: Request method 'POST' is not supported (allowed DELETE,GET,PUT; account ${rider.id}; agent Ktor client)",
                "INFO 405 POST /: Request method 'POST' is not supported (allowed GET,HEAD; no account; agent Ktor client)",
            ),
            logs.lines,
        )
    }

    @Test
    fun `refusals log their reason, unknown paths their cause`() {
        call(HttpMethod.PUT, "/stables/me", """{"name":"X"}""", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.GET, "/nope", as_ = rider.id).andExpect { status { isNotFound() } }

        assertEquals(
            listOf(
                "INFO 403 PUT /stables/me: Not allowed (account ${rider.id}; agent Ktor client)",
                "INFO 404 GET /nope: No static resource nope for request '/nope'. (account ${rider.id}; agent Ktor client)",
            ),
            logs.lines,
        )
    }

    @Test
    fun `invalid bodies log only the fields, never the rejected values`() {
        call(HttpMethod.POST, "/auth/register", """{"email":"neu@hufly.test","password":"geheim","displayName":"Neu"}""")
            .andExpect { status { isBadRequest() } }

        assertEquals(listOf("INFO 400 POST /auth/register: invalid password (no account; agent Ktor client)"), logs.lines)
    }

    @Test
    fun `missing or expired logins are not logged`() {
        call(HttpMethod.GET, "/events/sync").andExpect { status { isUnauthorized() } }

        assertEquals(emptyList(), logs.lines)
    }
}
