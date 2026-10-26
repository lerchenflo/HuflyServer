package com.lerchenflo.hufly.server.core.security

import ch.qos.logback.classic.Level
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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/** Failed requests are logged with what is needed to find their cause, never with request bodies or rejected values. */
@SpringBootTest(
    properties = [
        "operator.path=/office-t3st", "operator.username=operator", "operator.password=operator-secret-123",
        "ratelimit.operator.max-failures-per-ip=2",
    ],
)
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class, FailedRequestLogTest.CrashController::class)
class FailedRequestLogTest {

    @RestController
    class CrashController {
        @GetMapping("/test/crash")
        fun crash(): String = error("details that stay out of the log")
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    private val admin = testUser()
    private val rider = testUser()
    private lateinit var logs: LogCapture
    private lateinit var springLogs: LogCapture

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        listOf(admin, rider).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        logs = LogCapture(FailedRequestLog::class.java, Level.DEBUG)
        springLogs = LogCapture("org.springframework")
    }

    @AfterTest
    fun tearDown() {
        logs.close()
        springLogs.close()
    }

    private fun call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        as_: org.bson.types.ObjectId? = null,
        basic: String? = null,
        ip: String = "127.0.0.1",
    ) =
        mockMvc.request(method, path) {
            with { it.remoteAddr = ip; it }
            header("User-Agent", "Ktor client")
            if (as_ != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(as_)}")
            if (basic != null) header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.toByteArray()))
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `a method the route does not take is logged with path, cause, allowed methods and app`() {
        call(HttpMethod.POST, "/events/sync", as_ = rider.id).andExpect { status { isMethodNotAllowed() } }

        assertEquals(
            listOf(
                // Also matches PUT/DELETE /events/{eventId}.
                "INFO 405 POST /events/sync: Request method 'POST' is not supported (allowed DELETE,GET,PUT; agent Ktor client)",
            ),
            logs.lines,
        )
    }

    @Test
    fun `wrong paths, methods or types without a login are bots probing the website, logged at DEBUG only`() {
        call(HttpMethod.POST, "/").andExpect { status { isMethodNotAllowed() } }
        call(HttpMethod.GET, "/assets/.env").andExpect { status { isNotFound() } }
        mockMvc.request(HttpMethod.GET, "/") { header("Accept", "application/json") }.andExpect { status { isNotAcceptable() } }

        assertEquals(
            listOf(
                "DEBUG 405 POST /: Request method 'POST' is not supported (allowed GET,HEAD; agent Ktor client)",
                "DEBUG 404 GET /assets/.env: No static resource assets/.env for request '/assets/.env'. (agent Ktor client)",
                "DEBUG 406 GET / (agent none)",
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
                "INFO 403 PUT /stables/me: Not allowed (agent Ktor client)",
                "INFO 404 GET /nope: No static resource nope for request '/nope'. (agent Ktor client)",
            ),
            logs.lines,
        )
    }

    @Test
    fun `invalid bodies log only the fields, never the rejected values`() {
        call(HttpMethod.POST, "/auth/register", """{"email":"neu@hufly.test","password":"geheim","displayName":"Neu"}""")
            .andExpect { status { isBadRequest() } }

        assertEquals(listOf("INFO 400 POST /auth/register: invalid password (agent Ktor client)"), logs.lines)
    }

    @Test
    fun `the account goes into the line's user column, not into the message`() {
        call(HttpMethod.PUT, "/stables/me", """{"name":"X"}""", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.POST, "/auth/register", """{"email":"neu@hufly.test","password":"geheim","displayName":"Neu"}""")
            .andExpect { status { isBadRequest() } }

        assertEquals(listOf(rider.id.toHexString(), null), logs.users)
    }

    @Test
    fun `missing or expired logins are logged at DEBUG only`() {
        call(HttpMethod.GET, "/events/sync").andExpect { status { isUnauthorized() } }

        assertEquals(listOf("DEBUG 401 GET /events/sync (agent Ktor client)"), logs.lines)
    }

    @Test
    fun `exceptions no handler answers are logged as 500 with their class, never their message`() {
        assertFails { call(HttpMethod.GET, "/test/crash", as_ = rider.id) }

        assertEquals(listOf("WARN 500 GET /test/crash: IllegalStateException (agent Ktor client)"), logs.lines)
    }

    @Test
    fun `Spring writes no lines of its own for failed requests, they would repeat the line without the path`() {
        call(HttpMethod.POST, "/events/sync", as_ = rider.id).andExpect { status { isMethodNotAllowed() } }
        call(HttpMethod.GET, "/nope", as_ = rider.id).andExpect { status { isNotFound() } }
        call(HttpMethod.POST, "/auth/register", """{"email":"neu@hufly.test","password":"geheim","displayName":"Neu"}""")
            .andExpect { status { isBadRequest() } }
        mockMvc.request(HttpMethod.POST, "/auth/login") { contentType = MediaType.TEXT_PLAIN; content = "x" }
            .andExpect { status { isUnsupportedMediaType() } }

        assertEquals(emptyList(), springLogs.lines)
    }

    @Test
    fun `the operator area logs wrong logins and blocks at WARN, never its secret path`() {
        call(HttpMethod.GET, "/office-t3st", ip = "203.0.113.20").andExpect { status { isUnauthorized() } }
        repeat(2) {
            call(HttpMethod.GET, "/office-t3st/api/stables", basic = "operator:wrong-password", ip = "203.0.113.20")
                .andExpect { status { isUnauthorized() } }
        }
        call(HttpMethod.GET, "/office-t3st/api/stables", basic = "operator:operator-secret-123", ip = "203.0.113.20")
            .andExpect { status { isTooManyRequests() } }
        call(HttpMethod.GET, "/office-t3st/nope", basic = "operator:operator-secret-123", ip = "203.0.113.21")
            .andExpect { status { isNotFound() } }

        assertEquals(
            listOf(
                // The browser's first try without credentials is no failure.
                "WARN 401 GET <operator path> (agent Ktor client)",
                "WARN 401 GET <operator path> (agent Ktor client)",
                "WARN 429 GET <operator path> (agent Ktor client)",
                "INFO 404 GET <operator path>: NoResourceFoundException (agent Ktor client)",
            ),
            logs.lines,
        )
        assertTrue((logs.lines + springLogs.lines).none { "office-t3st" in it })
    }
}
