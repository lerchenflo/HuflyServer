package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.request
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** USR-1, USR-2, USR-5, USR-6, USR-7: HTTP mapping and validation. Business rules live in UserServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class UserAdminControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var hashEncoder: HashEncoder
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository

    private val admin = testUser(email = "admin@hufly.test")
    private lateinit var rider: com.lerchenflo.hufly.server.user.model.User

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        rider = testUser(email = "rider@hufly.test", hashedPassword = hashEncoder.encode("OldSecret1"))
        userRepository.save(admin)
        userRepository.save(rider)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, userId: ObjectId = admin.id) =
        mockMvc.request(method, path) {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    @Test
    fun `create user answers with the user and the generated password`() {
        call(HttpMethod.POST, "/users", """{"email":"new@hufly.test","displayName":"Neu","phoneNumber":null,"roleTagIds":[]}""").andExpect {
            status { isOk() }
            jsonPath("$.user.email") { value("new@hufly.test") }
            jsonPath("$.generatedPassword") { isString() }
            jsonPath("$.user.hashedPassword") { doesNotExist() }
        }
    }

    @Test
    fun `create user with an invalid email answers 400`() {
        call(HttpMethod.POST, "/users", """{"email":"not-an-email","displayName":"Neu","roleTagIds":[]}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create user with a malformed role tag id answers 400`() {
        call(HttpMethod.POST, "/users", """{"email":"new@hufly.test","displayName":"Neu","roleTagIds":["nope"]}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create user as member answers 403`() {
        call(HttpMethod.POST, "/users", """{"email":"new@hufly.test","displayName":"Neu","roleTagIds":[]}""", userId = rider.id)
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `edit user answers with the updated user`() {
        val body = """{"email":"rider@hufly.test","displayName":"Renamed","phoneNumber":"+43","profilePictureUrl":null,"roleTagIds":[]}"""
        call(HttpMethod.PUT, "/users/${rider.id.toHexString()}", body).andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("Renamed") }
        }
    }

    @Test
    fun `edit user with a malformed id answers 400`() {
        val body = """{"email":"x@hufly.test","displayName":"X","roleTagIds":[]}"""
        call(HttpMethod.PUT, "/users/not-an-id", body).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `edit user to a taken email answers 409`() {
        val body = """{"email":"admin@hufly.test","displayName":"X","roleTagIds":[]}"""
        call(HttpMethod.PUT, "/users/${rider.id.toHexString()}", body).andExpect { status { isConflict() } }
    }

    @Test
    fun `delete user answers 200 and marks them deleted`() {
        mockMvc.delete("/users/${rider.id.toHexString()}") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(admin.id)}")
        }.andExpect { status { isOk() } }

        assertTrue(userRepository.findById(rider.id)!!.deleted)
    }

    @Test
    fun `own account deletion answers 204, a wrong password 400 with a code`() {
        call(HttpMethod.DELETE, "/users/me", """{"password":"wrong"}""", userId = rider.id).andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("WRONG_PASSWORD") }
        }
        call(HttpMethod.DELETE, "/users/me", """{"password":""}""", userId = rider.id).andExpect { status { isBadRequest() } }

        call(HttpMethod.DELETE, "/users/me", """{"password":"OldSecret1"}""", userId = rider.id).andExpect { status { isNoContent() } }

        assertTrue(userRepository.findById(rider.id)!!.deleted)
        call(HttpMethod.GET, "/users/me", userId = rider.id).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `own account deletion without a token answers 401`() {
        mockMvc.delete("/users/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"OldSecret1"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `password reset answers with the new password`() {
        call(HttpMethod.POST, "/users/${rider.id.toHexString()}/password-reset").andExpect {
            status { isOk() }
            jsonPath("$.generatedPassword") { isString() }
        }
    }

    @Test
    fun `own profile edit answers with the updated user`() {
        val body = """{"email":"me@hufly.test","displayName":"Me","phoneNumber":null,"profilePictureUrl":null}"""
        call(HttpMethod.PUT, "/users/me", body, userId = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.email") { value("me@hufly.test") }
        }
    }

    @Test
    fun `own password change answers 200`() {
        call(HttpMethod.POST, "/users/me/password", """{"oldPassword":"OldSecret1","newPassword":"NewSecret1"}""", userId = rider.id)
            .andExpect { status { isOk() } }
    }

    @Test
    fun `own password change with a short new password answers 400`() {
        call(HttpMethod.POST, "/users/me/password", """{"oldPassword":"OldSecret1","newPassword":"short"}""", userId = rider.id)
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `admin uploads and removes a member's profile picture`() {
        val auth = "Bearer ${jwtService.generateAccessToken(admin.id)}"
        val path = "/users/${rider.id.toHexString()}/picture"

        mockMvc.multipart(HttpMethod.PUT, path) {
            file(MockMultipartFile("picture", "kid.png", "image/png", com.lerchenflo.hufly.server.core.picture.testPng()))
            header("Authorization", auth)
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(rider.id.toHexString()) }
            jsonPath("$.profilePictureUrl") { value(org.hamcrest.Matchers.startsWith("$path?v=")) }
        }

        call(HttpMethod.DELETE, path).andExpect {
            status { isOk() }
            jsonPath("$.profilePictureUrl") { value(null) }
        }
        call(HttpMethod.DELETE, path, userId = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.DELETE, "/users/nope/picture").andExpect { status { isBadRequest() } }
    }
}
