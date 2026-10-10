package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.picture.testPng
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import kotlin.test.BeforeTest
import kotlin.test.Test

/** OFF-4, TAG-4, USR-6 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class UserControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository

    private val admin = testUser(email = "admin@hufly.test")
    private val trainerTag = testTag(permissions = setOf(Permission.EVENT_VIEW))
    private val rider = testUser(email = "rider@hufly.test", roleTagIds = listOf(trainerTag.id), updatedAt = 1234)

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        tagRepository.save(trainerTag)
        stableRepository.save(testStable(adminUserId = admin.id, name = "Hof Lerchenfeld"))
    }

    private fun getMe(userId: org.bson.types.ObjectId) =
        mockMvc.get("/users/me") { header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}") }

    @Test
    fun `me answers with the own user without password hash`() {
        getMe(rider.id).andExpect {
            status { isOk() }
            jsonPath("$.user.id") { value(rider.id.toHexString()) }
            jsonPath("$.user.stableId") { value(rider.stableId.toHexString()) }
            jsonPath("$.user.email") { value("rider@hufly.test") }
            jsonPath("$.user.displayName") { value("rider") }
            jsonPath("$.user.roleTagIds[0]") { value(trainerTag.id.toHexString()) }
            jsonPath("$.user.updatedAt") { value(1234) }
            jsonPath("$.user.hashedPassword") { doesNotExist() }
        }
    }

    @Test
    fun `me says the stable manages the login it created`() {
        getMe(rider.id).andExpect {
            status { isOk() }
            jsonPath("$.user.loginManagedByStable") { value(true) }
        }
    }

    @Test
    fun `me tells a member they are not admin and lists their permissions`() {
        getMe(rider.id).andExpect {
            jsonPath("$.isAdmin") { value(false) }
            jsonPath("$.permissions.length()") { value(1) }
            jsonPath("$.permissions[0]") { value("EVENT_VIEW") }
        }
    }

    @Test
    fun `me tells the admin they are admin with every permission`() {
        getMe(admin.id).andExpect {
            jsonPath("$.isAdmin") { value(true) }
            jsonPath("$.permissions.length()") { value(Permission.entries.size) }
        }
    }

    @Test
    fun `me includes the own stable`() {
        getMe(rider.id).andExpect {
            jsonPath("$.stable.id") { value(rider.stableId.toHexString()) }
            jsonPath("$.stable.name") { value("Hof Lerchenfeld") }
            jsonPath("$.stable.subscriptionStatus") { value("TRIAL") }
            jsonPath("$.stable.subscriptionValidUntil") { value(null) }
        }
    }

    @Test
    fun `me of a deleted login answers 401`() {
        userRepository.accounts.save(userRepository.accounts.findById(rider.accountId)!!.copy(deleted = true))

        getMe(rider.id).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `me of a login without a stable answers 403 NO_STABLE`() {
        userRepository.save(rider.copy(deleted = true))

        getMe(rider.id).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NO_STABLE") }
        }
    }

    @Test
    fun `own profile picture upload, download by a stable member and removal`() {
        val auth = "Bearer ${jwtService.generateAccessToken(rider.id)}"
        val path = "/users/${rider.id.toHexString()}/picture"

        mockMvc.multipart(HttpMethod.PUT, "/users/me/picture") {
            file(MockMultipartFile("picture", "me.png", "image/png", testPng()))
            header("Authorization", auth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.profilePictureUrl") { value(org.hamcrest.Matchers.startsWith("$path?v=")) }
        }
        mockMvc.get(path) { header("Authorization", "Bearer ${jwtService.generateAccessToken(admin.id)}") }.andExpect {
            status { isOk() }
            content { contentType("image/jpeg") }
        }
        mockMvc.delete("/users/me/picture") { header("Authorization", auth) }.andExpect {
            status { isOk() }
            jsonPath("$.profilePictureUrl") { value(null) }
        }
        mockMvc.get(path) { header("Authorization", auth) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `me tells the user to choose an own password, the user object never does`() {
        getMe(rider.id).andExpect {
            jsonPath("$.mustChangePassword") { value(false) }
            jsonPath("$.user.mustChangePassword") { doesNotExist() }
        }
        userRepository.accounts.save(userRepository.accounts.findById(rider.id)!!.copy(mustChangePassword = true))

        getMe(rider.id).andExpect {
            status { isOk() }
            jsonPath("$.mustChangePassword") { value(true) }
            jsonPath("$.user.mustChangePassword") { doesNotExist() }
        }
    }
}
