package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
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

/** OFF-5, OFF-6: user settings are an opaque key-value map owned by the app. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class UserSettingsControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var settingsRepository: FakeUserSettingsRepository

    private val admin = testUser()
    private val anna = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        settingsRepository.settings.clear()
        userRepository.save(admin)
        userRepository.save(anna)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, body: String? = null, userId: ObjectId = anna.id) =
        mockMvc.request(method, "/users/me/settings") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `settings start empty`() {
        call(HttpMethod.GET).andExpect {
            status { isOk() }
            jsonPath("$.values") { isMap() }
            jsonPath("$.values.length()") { value(0) }
            jsonPath("$.updatedAt") { value(null) }
        }
    }

    @Test
    fun `put replaces the whole map and get returns it`() {
        call(HttpMethod.PUT, """{"values":{"theme":"dark","calendarView":"LIST"}}""").andExpect { status { isOk() } }
        call(HttpMethod.PUT, """{"values":{"theme":"light"}}""").andExpect {
            status { isOk() }
            jsonPath("$.values.theme") { value("light") }
            jsonPath("$.updatedAt") { isNumber() }
        }

        call(HttpMethod.GET).andExpect {
            jsonPath("$.values.length()") { value(1) }
            jsonPath("$.values.theme") { value("light") }
        }
    }

    @Test
    fun `settings are per user`() {
        call(HttpMethod.PUT, """{"values":{"theme":"dark"}}""").andExpect { status { isOk() } }

        call(HttpMethod.GET, userId = admin.id).andExpect { jsonPath("$.values.length()") { value(0) } }
    }

    @Test
    fun `too many keys or too long values answer 400`() {
        val manyKeys = (0..100).joinToString(",", "{\"values\":{", "}}") { "\"k$it\":\"v\"" }
        call(HttpMethod.PUT, manyKeys).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, """{"values":{"k":"${"x".repeat(5001)}"}}""").andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, """{"values":{"${"k".repeat(101)}":"v"}}""").andExpect { status { isBadRequest() } }
    }
}
