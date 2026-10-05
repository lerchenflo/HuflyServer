package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
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
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `an ACTIVITY tag keeps its default interval`() {
        call(HttpMethod.POST, "/tags", """{"name":"Hufschmied","type":"ACTIVITY","color":"#8d6e63","defaultIntervalDays":42}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(42) }
        }
    }

    @Test
    fun `a tag without a default interval answers null`() {
        call(HttpMethod.POST, "/tags", """{"name":"Hafer","type":"FOOD","color":"#8d6e63"}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(null) }
        }
    }

    @Test
    fun `editing a tag with a null default interval clears it`() {
        val tag = tagRepository.save(testTag(type = TagType.ACTIVITY).copy(defaultIntervalDays = 42))

        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Hufschmied","color":"#8d6e63","permissions":[],"defaultIntervalDays":null}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(null) }
        }
        assertNull(tagRepository.findById(tag.id)?.defaultIntervalDays)
    }

    @Test
    fun `editing an ACTIVITY tag sets its default interval`() {
        val tag = tagRepository.save(testTag(type = TagType.ACTIVITY))

        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Impfung","color":"#8d6e63","permissions":[],"defaultIntervalDays":182}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(182) }
        }
    }

    @Test
    fun `a default interval outside 1 to 3650 days answers 400`() {
        val tag = tagRepository.save(testTag(type = TagType.ACTIVITY))
        for (days in listOf(0, 3651)) {
            call(HttpMethod.POST, "/tags", """{"name":"X","type":"ACTIVITY","color":"#8d6e63","defaultIntervalDays":$days}""")
                .andExpect { status { isBadRequest() } }
            call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"X","color":"#8d6e63","permissions":[],"defaultIntervalDays":$days}""")
                .andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `tags other than ACTIVITY store no default interval`() {
        val food = tagRepository.save(testTag(type = TagType.FOOD))

        call(HttpMethod.POST, "/tags", """{"name":"Hafer","type":"FOOD","color":"#8d6e63","defaultIntervalDays":42}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(null) }
        }
        call(HttpMethod.PUT, "/tags/${food.id.toHexString()}", """{"name":"Heu","color":"#8d6e63","permissions":[],"defaultIntervalDays":42}""").andExpect {
            status { isOk() }
            jsonPath("$.defaultIntervalDays") { value(null) }
        }
        assertNull(tagRepository.findById(food.id)?.defaultIntervalDays)
    }

    @Test
    fun `a tag stores its icon and an edit without one clears it`() {
        call(HttpMethod.POST, "/tags", """{"name":"Heu","type":"FOOD","color":"#8bc34a","permissions":[],"icon":"hay"}""").andExpect {
            status { isOk() }
            jsonPath("$.icon") { value("hay") }
        }
        val tag = tagRepository.tags.single()
        assertEquals("hay", tag.icon)

        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Heu","color":"#8bc34a","permissions":[]}""").andExpect {
            status { isOk() }
            jsonPath("$.icon") { value(null) }
        }
        assertNull(tagRepository.findById(tag.id)!!.icon)
    }

    @Test
    fun `an icon outside 1 to 40 lowercase letters or underscores answers 400`() {
        listOf("\"\"", "\"Hay\"", "\"hay-1\"", "\"${"a".repeat(41)}\"").forEach { icon ->
            call(HttpMethod.POST, "/tags", """{"name":"Heu","type":"FOOD","color":"#8bc34a","permissions":[],"icon":$icon}""")
                .andExpect { status { isBadRequest() } }
        }
        val tag = tagRepository.save(testTag())
        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Heu","color":"#8bc34a","permissions":[],"icon":"Hay"}""")
            .andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/tags/${tag.id.toHexString()}", """{"name":"Heu","color":"#8bc34a","permissions":[],"icon":"riding_hat"}""")
            .andExpect { status { isOk() } }
    }
}
