package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
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

/** TAG-2: HTTP mapping and validation. Business rules live in TagServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class TagAdminControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository

    private val admin = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        userRepository.save(admin)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, path: String, body: String? = null) =
        mockMvc.request(method, path) {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(admin.id)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `create tag answers with the tag`() {
        call(HttpMethod.POST, "/tags", """{"name":"Reitlehrer","type":"USER_ROLE","color":"#AA3300","permissions":["EVENT_VIEW"]}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Reitlehrer") }
            jsonPath("$.permissions[0]") { value("EVENT_VIEW") }
        }
    }

    @Test
    fun `create tag with an unknown permission answers 400`() {
        call(HttpMethod.POST, "/tags", """{"name":"X","type":"USER_ROLE","color":"#AA3300","permissions":["HORSE_ADD"]}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create tag with a malformed color answers 400`() {
        call(HttpMethod.POST, "/tags", """{"name":"X","type":"FOOD","color":"red","permissions":[]}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `edit tag answers with the updated tag`() {
        val tag = tagRepository.save(testTag())

        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Helfer","color":"#123456","permissions":[]}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Helfer") }
        }
    }

    @Test
    fun `delete tag answers 200`() {
        val tag = tagRepository.save(testTag())

        call(HttpMethod.DELETE, "/tags/${tag.id.toHexString()}").andExpect { status { isOk() } }
    }

    @Test
    fun `delete tag with a malformed id answers 400`() {
        call(HttpMethod.DELETE, "/tags/nope").andExpect { status { isBadRequest() } }
    }
}
