package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.core.sync.MAX_SYNC_CLIENT_ENTRIES
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.BeforeTest
import kotlin.test.Test

/** OFF-2, OFF-3, BIZ-4: users and tags sync, limited to the requester's stable. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class UserSyncControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository

    private val admin = testUser(updatedAt = 100)
    private val rider = testUser(updatedAt = 200)
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)
    private val removed = testUser(deleted = true)

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        listOf(admin, rider, foreigner, removed).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun sync(path: String, body: String, userId: ObjectId = rider.id, query: String = "") =
        mockMvc.post("$path$query") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    @Test
    fun `user sync returns all users of the own stable only`() {
        sync("/users/sync", "[]").andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(2) }
            jsonPath("$.updatedEntries[0].id") { value(rider.id.toHexString()) }
            jsonPath("$.updatedEntries[1].id") { value(admin.id.toHexString()) }
            jsonPath("$.updatedEntries[0].hashedPassword") { doesNotExist() }
            jsonPath("$.moreEntries") { value(false) }
        }
    }

    @Test
    fun `user sync tells whether the stable manages each member's login`() {
        val login = userRepository.accounts.save(testAccount(email = "self@hufly.test"))
        val self = userRepository.save(testUser(email = "self@hufly.test", updatedAt = 50).copy(accountId = login.id))

        sync("/users/sync", "[]").andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].id") { value(rider.id.toHexString()) }
            jsonPath("$.updatedEntries[0].loginManagedByStable") { value(true) }
            jsonPath("$.updatedEntries[2].id") { value(self.id.toHexString()) }
            jsonPath("$.updatedEntries[2].loginManagedByStable") { value(false) }
        }
    }

    @Test
    fun `user sync reports deleted and foreign users as deleted`() {
        val body = """[{"id":"${removed.id.toHexString()}","timeStamp":"0"},{"id":"${foreigner.id.toHexString()}","timeStamp":"0"}]"""

        sync("/users/sync", body).andExpect {
            jsonPath("$.deletedEntries.length()") { value(2) }
        }
    }

    @Test
    fun `user sync skips users the client already has`() {
        val body = """[{"id":"${admin.id.toHexString()}","timeStamp":"100"},{"id":"${rider.id.toHexString()}","timeStamp":"200"}]"""

        sync("/users/sync", body).andExpect {
            jsonPath("$.updatedEntries.length()") { value(0) }
        }
    }

    @Test
    fun `user sync rejects a negative page`() {
        sync("/users/sync", "[]", query = "?page=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `user sync rejects an oversized page`() {
        sync("/users/sync", "[]", query = "?page_size=100000").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `user sync rejects more client entries than the cap`() {
        val body = (0..MAX_SYNC_CLIENT_ENTRIES).joinToString(",", "[", "]") { """{"id":"$it","timeStamp":"0"}""" }

        sync("/users/sync", body).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `tag sync rejects more client entries than the cap`() {
        val body = (0..MAX_SYNC_CLIENT_ENTRIES).joinToString(",", "[", "]") { """{"id":"$it","timeStamp":"0"}""" }

        sync("/tags/sync", body).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `every id-timestamp sync validates each client entry`() {
        val endpoints = listOf("/users/sync", "/tags/sync", "/horses/sync", "/foodplans/sync", "/paddocks/sync", "/horsegroups/sync", "/horseconflicts/sync")
        listOf("""[{"id":"","timeStamp":0}]""", """[{"id":"${"x".repeat(101)}","timeStamp":0}]""").forEach { body ->
            endpoints.forEach { endpoint ->
                sync(endpoint, body).andExpect { status { isBadRequest() } }
            }
        }
    }

    @Test
    fun `user sync without token answers 401`() {
        mockMvc.post("/users/sync") {
            contentType = MediaType.APPLICATION_JSON
            content = "[]"
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `tag sync returns the non deleted tags of the own stable`() {
        val role = tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT)))
        tagRepository.save(testTag(stableId = OTHER_STABLE_ID))
        tagRepository.save(testTag(deleted = true))

        sync("/tags/sync", "[]").andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(1) }
            jsonPath("$.updatedEntries[0].id") { value(role.id.toHexString()) }
            jsonPath("$.updatedEntries[0].type") { value("USER_ROLE") }
            jsonPath("$.updatedEntries[0].permissions[0]") { value("HORSE_EDIT") }
            jsonPath("$.updatedEntries[0].color") { value("#888888") }
            jsonPath("$.updatedEntries[0].defaultIntervalDays") { value(null) }
        }
    }

    @Test
    fun `tag sync carries the default interval of ACTIVITY tags`() {
        tagRepository.save(testTag(type = TagType.ACTIVITY).copy(defaultIntervalDays = 42))

        sync("/tags/sync", "[]").andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].defaultIntervalDays") { value(42) }
        }
    }

    @Test
    fun `tag sync is open to members of every role`() {
        tagRepository.save(testTag(type = TagType.FOOD))

        sync("/tags/sync", "[]", userId = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(1) }
        }
    }
}
