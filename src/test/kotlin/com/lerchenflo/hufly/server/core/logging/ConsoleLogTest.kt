package com.lerchenflo.hufly.server.core.logging

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeAccountRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** The console layout of `logback-spring.xml`, the same as SchneaggchatV3server's AppLogger. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
@ExtendWith(OutputCaptureExtension::class)
class ConsoleLogTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var accountRepository: FakeAccountRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    private val rita = testAccount().copy(displayName = "Rita Reiter")

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        // Spring caches contexts, so the lookup may still point at another context's accounts
        LogNamesSetup(accountRepository)
        accountRepository.save(rita)
        userRepository.save(testUser(id = rita.id))
        stableRepository.save(testStable(adminUserId = ObjectId.get()))
    }

    /** Lines are printed by a background worker, so they show up a moment after the call. */
    private fun awaitLine(output: CapturedOutput, part: String): String {
        val ansi = Regex("\u001B\\[[;\\d]*m")
        repeat(250) {
            output.out.lineSequence().map { it.replace(ansi, "") }.firstOrNull { part in it }?.let { return it }
            Thread.sleep(20)
        }
        fail("No log line with '$part' in:\n${output.out}")
    }

    @Test
    fun `a line shows the date with dots, the level, the requesting account by name and the message`(output: CapturedOutput) {
        mockMvc.request(HttpMethod.PUT, "/stables/me") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(rita.id)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"X"}"""
        }.andExpect { status { isForbidden() } }

        val line = awaitLine(output, "403 PUT /stables/me")
        val expected = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}\.\d{3} INFO {4}Rita Reiter {15}\| FailedRequestLog: 403 PUT /stables/me: Not allowed \(agent none\)""")
        assertTrue(expected.matches(line), line)
    }

    @Test
    fun `a line without a login shows a dash, and accounts inside the message by name`(output: CapturedOutput) {
        LoggerFactory.getLogger(javaClass).info("Push note to {}", LogUser(rita.id))

        val line = awaitLine(output, "Push note to")
        val expected = Regex("""\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}:\d{2}\.\d{3} INFO {4}- {25}\| ConsoleLogTest: Push note to Rita Reiter""")
        assertTrue(expected.matches(line), line)
    }
}
