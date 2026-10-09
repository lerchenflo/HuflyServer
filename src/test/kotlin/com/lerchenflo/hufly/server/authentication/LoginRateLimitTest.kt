package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Brute-force protection: failed logins per email and per IP, and failed operator logins per IP. */
@SpringBootTest(
    properties = [
        "ratelimit.login.max-failures-per-email=3",
        "ratelimit.login.max-failures-per-ip=5",
        "ratelimit.operator.max-failures-per-ip=2",
        "operator.path=/office-t3st", "operator.username=operator", "operator.password=operator-secret-123",
    ],
)
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class LoginRateLimitTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var hashEncoder: HashEncoder
    @Autowired lateinit var userRepository: FakeUserRepository

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        userRepository.saveWithLogin(testUser(email = "anna@hufly.test"), hashEncoder.encode("Secret123"))
        userRepository.saveWithLogin(testUser(email = "ben@hufly.test"), hashEncoder.encode("Secret123"))
    }

    private fun login(email: String, password: String, ip: String = "203.0.113.7") = mockMvc.post("/auth/login") {
        with { it.remoteAddr = ip; it }
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"$password"}"""
    }

    @Test
    fun `too many failures for one email block it, even with the right password`() {
        repeat(3) { login("anna@hufly.test", "wrong", ip = "203.0.113.${it + 1}").andExpect { status { isUnauthorized() } } }

        login("anna@hufly.test", "Secret123", ip = "198.51.100.9").andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
        }
        login("ben@hufly.test", "Secret123", ip = "198.51.100.9").andExpect { status { isOk() } }
    }

    @Test
    fun `a successful login resets the email counter`() {
        repeat(2) { login("anna@hufly.test", "wrong") }
        login("anna@hufly.test", "Secret123").andExpect { status { isOk() } }

        repeat(2) { login("anna@hufly.test", "wrong").andExpect { status { isUnauthorized() } } }
        login("anna@hufly.test", "Secret123").andExpect { status { isOk() } }
    }

    @Test
    fun `too many failures from one IP block that IP for every email`() {
        repeat(5) { login("nobody$it@hufly.test", "wrong") }

        login("ben@hufly.test", "Secret123").andExpect { status { isTooManyRequests() } }
        login("ben@hufly.test", "Secret123", ip = "198.51.100.9").andExpect { status { isOk() } }
    }

    @Test
    fun `operator login is blocked per IP after failures`() {
        val wrong = "Basic " + Base64.getEncoder().encodeToString("operator:wrong-password".toByteArray())
        val right = "Basic " + Base64.getEncoder().encodeToString("operator:operator-secret-123".toByteArray())
        repeat(2) {
            mockMvc.get("/office-t3st/api/stables") { header("Authorization", wrong); with { r -> r.remoteAddr = "203.0.113.7"; r } }
                .andExpect { status { isUnauthorized() } }
        }

        mockMvc.get("/office-t3st/api/stables") { header("Authorization", right); with { r -> r.remoteAddr = "203.0.113.7"; r } }
            .andExpect { status { isTooManyRequests() } }
    }
}
