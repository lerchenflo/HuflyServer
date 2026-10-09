package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** USR-5: sessions per device. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class SessionControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var hashEncoder: HashEncoder
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var refreshTokenRepository: FakeRefreshTokenRepository

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        refreshTokenRepository.tokens.clear()
        val anna = userRepository.saveWithLogin(testUser(email = "anna@hufly.test"), hashEncoder.encode("Secret123"))
        stableRepository.save(testStable(adminUserId = anna.id))
    }

    private fun login(deviceName: String, deviceType: String): Map<*, *> {
        val body = mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"anna@hufly.test","password":"Secret123","deviceName":"$deviceName","deviceType":"$deviceType"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return objectMapper.readValue(body, Map::class.java)
    }

    @Test
    fun `login without device info still works`() {
        mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"anna@hufly.test","password":"Secret123"}"""
        }.andExpect { status { isOk() } }
        assertEquals("Unbekanntes Gerät", refreshTokenRepository.tokens.single().deviceName)
    }

    @Test
    fun `sessions list the devices and mark the current one`() {
        val phone = login("Pixel 7", "ANDROID")
        login("iPad", "IOS")

        mockMvc.get("/users/me/sessions") { header("Authorization", "Bearer ${phone["accessToken"]}") }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[?(@.current == true)].deviceName") { value("Pixel 7") }
            jsonPath("$[?(@.deviceName == 'iPad')].deviceType") { value("IOS") }
        }
    }

    @Test
    fun `ending another device's session logs it out`() {
        val phone = login("Pixel 7", "ANDROID")
        val tablet = login("iPad", "IOS")
        val tabletSession = refreshTokenRepository.tokens.single { it.deviceName == "iPad" }.id.toHexString()

        mockMvc.delete("/users/me/sessions/$tabletSession") { header("Authorization", "Bearer ${phone["accessToken"]}") }
            .andExpect { status { isOk() } }

        mockMvc.post("/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"${tablet["refreshToken"]}"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `logout everywhere needs a token and ends all sessions`() {
        val phone = login("Pixel 7", "ANDROID")
        login("iPad", "IOS")

        mockMvc.post("/auth/logout-all").andExpect { status { isUnauthorized() } }
        mockMvc.post("/auth/logout-all") { header("Authorization", "Bearer ${phone["accessToken"]}") }
            .andExpect { status { isOk() } }

        assertEquals(0, refreshTokenRepository.tokens.size)
    }

    @Test
    fun `password change over HTTP keeps the current device`() {
        val phone = login("Pixel 7", "ANDROID")
        login("iPad", "IOS")

        mockMvc.post("/users/me/password") {
            header("Authorization", "Bearer ${phone["accessToken"]}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"oldPassword":"Secret123","newPassword":"NewSecret1"}"""
        }.andExpect { status { isOk() } }

        assertEquals(listOf("Pixel 7"), refreshTokenRepository.tokens.map { it.deviceName })
    }

    @Test
    fun `unknown device type answers 400`() {
        mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"anna@hufly.test","password":"Secret123","deviceName":"X","deviceType":"TOASTER"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `login takes a device id, keeps it private and rejects ids over 64 characters`() {
        fun loginWith(deviceId: String) = mockMvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"anna@hufly.test","password":"Secret123","deviceName":"iPhone","deviceType":"IOS","deviceId":"$deviceId"}"""
        }

        val body = loginWith("install-1").andExpect { status { isOk() } }.andReturn().response.contentAsString
        loginWith("install-2").andExpect { status { isOk() } }
        loginWith("x".repeat(65)).andExpect { status { isBadRequest() } }

        val token = objectMapper.readValue(body, Map::class.java)["accessToken"]
        mockMvc.get("/users/me/sessions") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].deviceId") { doesNotExist() }
        }
    }
}
