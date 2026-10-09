package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import kotlin.test.BeforeTest
import kotlin.test.Test

@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class AuthControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var refreshTokenRepository: FakeRefreshTokenRepository
    @Autowired lateinit var hashEncoder: HashEncoder
    @Autowired lateinit var objectMapper: ObjectMapper

    private val annaId = ObjectId("66f000000000000000000001")

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        refreshTokenRepository.tokens.clear()
        userRepository.saveWithLogin(testUser(id = annaId, email = "anna@hufly.test"), hashEncoder.encode("Secret123"))
    }

    private fun postJson(path: String, json: String) = mockMvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun login(): Map<*, *> {
        val body = postJson("/auth/login", """{"email":"anna@hufly.test","password":"Secret123"}""")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        return objectMapper.readValue(body, Map::class.java)
    }

    @Test
    fun `login answers with the token pair the client expects`() {
        postJson("/auth/login", """{"email":"anna@hufly.test","password":"Secret123"}""").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.refreshToken") { isString() }
        }
    }

    @Test
    fun `login with wrong password answers 401`() {
        postJson("/auth/login", """{"email":"anna@hufly.test","password":"nope"}""").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `login with blank email answers 400`() {
        postJson("/auth/login", """{"email":"","password":"Secret123"}""").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `refresh answers with a new token pair`() {
        val refreshToken = login()["refreshToken"]

        postJson("/auth/refresh", """{"refreshToken":"$refreshToken"}""").andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.refreshToken") { isString() }
        }
    }

    @Test
    fun `refresh after logout answers 401`() {
        val refreshToken = login()["refreshToken"]

        postJson("/auth/logout", """{"refreshToken":"$refreshToken"}""").andExpect { status { isOk() } }

        postJson("/auth/refresh", """{"refreshToken":"$refreshToken"}""").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `protected endpoint without token answers 401`() {
        mockMvc.get("/users/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `protected endpoint with refresh token answers 401`() {
        val refreshToken = login()["refreshToken"]

        mockMvc.get("/users/me") { header("Authorization", "Bearer $refreshToken") }
            .andExpect { status { isUnauthorized() } }
    }
}
