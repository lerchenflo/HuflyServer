package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testStable
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Registrations per IP and wrong invite codes per login are limited, so neither accounts nor codes can be farmed. */
@SpringBootTest(properties = ["ratelimit.register.max-per-ip=2", "ratelimit.join-code.max-failures-per-account=3"])
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SignupRateLimitTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
    }

    private fun register(email: String, ip: String = "203.0.113.7") = mockMvc.post("/auth/register") {
        with { it.remoteAddr = ip; it }
        contentType = MediaType.APPLICATION_JSON
        content = """{"email":"$email","password":"Secret123","displayName":"X"}"""
    }

    private fun join(accountId: ObjectId, code: String) = mockMvc.put("/accounts/me/joinrequest") {
        header("Authorization", "Bearer ${jwtService.generateAccessToken(accountId)}")
        contentType = MediaType.APPLICATION_JSON
        content = """{"code":"$code"}"""
    }

    @Test
    fun `every registration counts per IP, failed ones too`() {
        register("a@hufly.test").andExpect { status { isCreated() } }
        register("a@hufly.test").andExpect { status { isConflict() } }

        register("b@hufly.test").andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
        }
        register("b@hufly.test", ip = "198.51.100.9").andExpect { status { isCreated() } }
    }

    @Test
    fun `too many wrong invite codes block the login, even with the right code`() {
        stableRepository.save(testStable(adminUserId = ObjectId.get()).copy(inviteCode = "ABCD2345"))
        val login = userRepository.accounts.save(testAccount()).id
        val other = userRepository.accounts.save(testAccount()).id

        repeat(3) { join(login, "ZZZZ2345").andExpect { status { isNotFound() } } }

        join(login, "ABCD2345").andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
        }
        join(other, "ABCD2345").andExpect { status { isOk() } }
    }
}
