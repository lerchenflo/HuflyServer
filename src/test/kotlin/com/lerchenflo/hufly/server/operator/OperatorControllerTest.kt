package com.lerchenflo.hufly.server.operator

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Onboarding website: the operator creates a stable with its first admin (BIZ-1, BIZ-2). */
@SpringBootTest(properties = ["operator.path=/office-t3st", "operator.username=operator", "operator.password=operator-secret-123"])
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class OperatorControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    private val stableAdmin = testUser(email = "admin@hufly.test")

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        userRepository.save(stableAdmin)
        stableRepository.save(testStable(adminUserId = stableAdmin.id, name = "Teststall"))
    }

    private fun basic(user: String = "operator", password: String = "operator-secret-123") =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private val createJson = """{"stableName":"Hof Lerchenfeld","adminEmail":"chef@hufly.test","adminDisplayName":"Chef"}"""

    @Test
    fun `without credentials the browser gets a login prompt`() {
        mockMvc.get("/office-t3st/api/stables").andExpect {
            status { isUnauthorized() }
            header { exists("WWW-Authenticate") }
        }
    }

    @Test
    fun `wrong password answers 401`() {
        mockMvc.get("/office-t3st/api/stables") { header("Authorization", basic(password = "wrong")) }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `an app token of a stable admin is not enough`() {
        mockMvc.get("/office-t3st/api/stables") { header("Authorization", "Bearer ${jwtService.generateAccessToken(stableAdmin.id)}") }
            .andExpect { status { is4xxClientError() } }
    }

    @Test
    fun `operator lists stables with their admin`() {
        mockMvc.get("/office-t3st/api/stables") { header("Authorization", basic()) }.andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Teststall") }
            jsonPath("$[0].adminEmail") { value("admin@hufly.test") }
            jsonPath("$[0].subscriptionStatus") { value("TRIAL") }
        }
    }

    @Test
    fun `operator creates a stable and the admin can log in with the generated password`() {
        val body = mockMvc.post("/office-t3st/api/stables") {
            header("Authorization", basic())
            contentType = MediaType.APPLICATION_JSON
            content = createJson
        }.andExpect {
            status { isOk() }
            jsonPath("$.stableName") { value("Hof Lerchenfeld") }
            jsonPath("$.adminEmail") { value("chef@hufly.test") }
        }.andReturn().response.contentAsString
        val password = objectMapper.readTree(body)["generatedPassword"].asString()

        mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"chef@hufly.test","password":"$password"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `a taken email answers 409`() {
        mockMvc.post("/office-t3st/api/stables") {
            header("Authorization", basic())
            contentType = MediaType.APPLICATION_JSON
            content = createJson.replace("chef@hufly.test", "admin@hufly.test")
        }.andExpect { status { isConflict() } }
    }

    @Test
    fun `form posts are rejected so other websites cannot use cached credentials`() {
        mockMvc.post("/office-t3st/api/stables") {
            header("Authorization", basic())
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            content = "stableName=X&adminEmail=x@hufly.test&adminDisplayName=X"
        }.andExpect { status { isUnsupportedMediaType() } }
    }

    @Test
    fun `invalid input answers 400`() {
        mockMvc.post("/office-t3st/api/stables") {
            header("Authorization", basic())
            contentType = MediaType.APPLICATION_JSON
            content = createJson.replace("chef@hufly.test", "not-an-email")
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `obvious admin paths do not exist`() {
        for (path in listOf("/operator", "/admin", "/operator/stables", "/office-t3st/../operator")) {
            mockMvc.get(path) { header("Authorization", basic()) }.andExpect { status { is4xxClientError() } }
        }
    }

    @Test
    fun `the page itself needs the operator login`() {
        mockMvc.get("/office-t3st").andExpect { status { isUnauthorized() } }
        mockMvc.get("/office-t3st") { header("Authorization", basic()) }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }
}
