package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeJoinRequestRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.request
import tools.jackson.databind.ObjectMapper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Self-signup: register, join a stable with its invite code (admin accepts or declines) or create an own stable. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class SignupControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var hashEncoder: HashEncoder
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var joinRequestRepository: FakeJoinRequestRepository

    private val admin = testUser(email = "admin@hufly.test")
    private val rider = testUser(email = "rider@hufly.test")

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        joinRequestRepository.requests.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        stableRepository.save(testStable(adminUserId = admin.id).copy(inviteCode = "ABCD2345", place = "Dornbirn"))
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, as_: ObjectId? = null, stable: String? = null): ResultActionsDsl =
        mockMvc.request(method, path) {
            if (as_ != null) header("Authorization", "Bearer ${jwtService.generateAccessToken(as_)}")
            if (stable != null) header("X-Stable-Id", stable)
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun json(result: ResultActionsDsl) = objectMapper.readTree(result.andReturn().response.contentAsString)

    /** A fresh self-registered login without a stable; answers its account id. */
    private fun registered(email: String = "neu@hufly.test"): ObjectId {
        val tokens = json(call(HttpMethod.POST, "/auth/register", """{"email":"$email","password":"Secret123","displayName":"Neu"}"""))
        return jwtService.userIdFromAccessToken(tokens["accessToken"].asString())!!
    }

    // Register

    @Test
    fun `registering answers a token pair for a login without a stable`() {
        call(HttpMethod.POST, "/auth/register", """{"email":"Neu@Hufly.test","password":"Secret123","displayName":" Neu "}""").andExpect {
            status { isCreated() }
            jsonPath("$.accessToken") { isString() }
            jsonPath("$.refreshToken") { isString() }
        }
        val login = userRepository.accounts.findByEmail("neu@hufly.test")!!
        assertEquals("Neu", login.displayName)
        assertTrue(hashEncoder.matches("Secret123", login.hashedPassword))
        assertNull(login.createdByStableId)
        assertTrue(userRepository.findByAccountIdAndDeletedFalse(login.id).isEmpty())

        call(HttpMethod.POST, "/auth/login", """{"email":"neu@hufly.test","password":"Secret123"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `a login without a stable gets 403 NO_STABLE on stable endpoints but reaches its own login data`() {
        val login = registered()

        call(HttpMethod.GET, "/users/me", as_ = login).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NO_STABLE") }
        }
        call(HttpMethod.POST, "/horses/sync", "[]", as_ = login).andExpect { status { isForbidden() } }
        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(login.toHexString()) }
            jsonPath("$.email") { value("neu@hufly.test") }
            jsonPath("$.displayName") { value("Neu") }
            jsonPath("$.mustChangePassword") { value(false) }
            jsonPath("$.joinRequest") { value(null) }
        }
        call(HttpMethod.GET, "/users/me/sessions", as_ = login).andExpect { status { isOk() } }
        call(HttpMethod.GET, "/users/me/settings", as_ = login).andExpect { status { isOk() } }
        call(HttpMethod.PUT, "/users/me/settings", """{"values":{"theme":"dark"}}""", as_ = login).andExpect { status { isOk() } }
        call(HttpMethod.POST, "/users/me/password", """{"oldPassword":"Secret123","newPassword":"Secret456"}""", as_ = login)
            .andExpect { status { isOk() } }
    }

    @Test
    fun `registering a taken email answers 409 EMAIL_IN_USE`() {
        call(HttpMethod.POST, "/auth/register", """{"email":"Rider@hufly.test","password":"Secret123","displayName":"X"}""").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_IN_USE") }
        }
    }

    @Test
    fun `registering checks its fields`() {
        listOf(
            """{"email":"neu@hufly.test","password":"short","displayName":"X"}""",
            """{"email":"no-email","password":"Secret123","displayName":"X"}""",
            """{"email":"neu@hufly.test","password":"Secret123","displayName":" "}""",
            """{"email":"neu@hufly.test","password":"Secret123","displayName":"${"x".repeat(101)}"}""",
            """{"email":"deleted-abc@deleted.invalid","password":"Secret123","displayName":"X"}""",
        ).forEach { body -> call(HttpMethod.POST, "/auth/register", body).andExpect { status { isBadRequest() } } }
        assertNull(userRepository.accounts.findByEmail("neu@hufly.test"))
    }

    // Join with an invite code

    @Test
    fun `a join request with the invite code shows the stable, case and dashes do not matter`() {
        val login = registered()

        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":" abcd-2345 "}""", as_ = login).andExpect {
            status { isOk() }
            jsonPath("$.stableId") { value(STABLE_ID.toHexString()) }
            jsonPath("$.stableName") { value("Hof Lerchenfeld") }
            jsonPath("$.stablePlace") { value("Dornbirn") }
            jsonPath("$.status") { value("PENDING") }
        }
        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect { jsonPath("$.joinRequest.status") { value("PENDING") } }
        call(HttpMethod.GET, "/stables/me/joinrequests", as_ = admin.id).andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].accountId") { value(login.toHexString()) }
            jsonPath("$[0].displayName") { value("Neu") }
            jsonPath("$[0].email") { value("neu@hufly.test") }
        }
    }

    @Test
    fun `an unknown invite code answers 404 INVALID_CODE`() {
        val login = registered()

        listOf("ZZZZ2345", "abc", "ABCD-234O").forEach { code ->
            call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"$code"}""", as_ = login).andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("INVALID_CODE") }
            }
        }
        assertTrue(joinRequestRepository.requests.isEmpty())
    }

    @Test
    fun `a member cannot ask to join the own stable again`() {
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = rider.id).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ALREADY_MEMBER") }
        }
        assertTrue(joinRequestRepository.requests.isEmpty())
    }

    @Test
    fun `a new request replaces the earlier one and withdrawing clears it`() {
        val login = registered()
        val other = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = ObjectId.get(), name = "Anderer").copy(inviteCode = "WXYZ6789"))
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login)

        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"WXYZ6789"}""", as_ = login).andExpect {
            jsonPath("$.stableId") { value(other.id.toHexString()) }
        }
        assertEquals(1, joinRequestRepository.requests.count { it.status == JoinRequestStatus.PENDING })
        call(HttpMethod.GET, "/stables/me/joinrequests", as_ = admin.id).andExpect { jsonPath("$.length()") { value(0) } }

        call(HttpMethod.DELETE, "/accounts/me/joinrequest", as_ = login).andExpect { status { isNoContent() } }
        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect { jsonPath("$.joinRequest") { value(null) } }
    }

    // A login the stable created belongs to the person once it joins another stable (loginManagedByStable flips)

    @Test
    fun `joining another stable re-stamps the login's row in the stable that created it`() {
        val foreignAdmin = userRepository.save(testUser(email = "chef@sonnenhof.test", stableId = OTHER_STABLE_ID))
        stableRepository.save(testStable(id = OTHER_STABLE_ID, adminUserId = foreignAdmin.id, name = "Sonnenhof").copy(inviteCode = "WXYZ6789"))
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"WXYZ6789"}""", as_ = rider.id))["id"].asString()

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = foreignAdmin.id).andExpect { status { isOk() } }

        assertTrue(userRepository.findById(rider.id)!!.updatedAt > 0L)
    }

    @Test
    fun `creating an own stable re-stamps the login's row in the stable that created it`() {
        call(HttpMethod.POST, "/accounts/me/stable", """{"name":"Eigener Hof"}""", as_ = rider.id).andExpect { status { isCreated() } }

        assertTrue(userRepository.findById(rider.id)!!.updatedAt > 0L)
    }

    // Admin answers

    @Test
    fun `the admin accepts a request with roles and the login enters the stable`() {
        val login = registered()
        val role = tagRepository.save(testTag(type = TagType.USER_ROLE))
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", """{"roleTagIds":["${role.id.toHexString()}"]}""", as_ = admin.id).andExpect {
            status { isOk() }
            jsonPath("$.email") { value("neu@hufly.test") }
            jsonPath("$.displayName") { value("Neu") }
            jsonPath("$.roleTagIds[0]") { value(role.id.toHexString()) }
        }
        call(HttpMethod.GET, "/users/me", as_ = login).andExpect {
            status { isOk() }
            jsonPath("$.stable.id") { value(STABLE_ID.toHexString()) }
            jsonPath("$.isAdmin") { value(false) }
        }
        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect { jsonPath("$.joinRequest") { value(null) } }
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = admin.id).andExpect { status { isNotFound() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/decline", as_ = admin.id).andExpect { status { isNotFound() } }
    }

    @Test
    fun `a declined request shows as declined until the login asks again`() {
        val login = registered()
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/decline", as_ = admin.id).andExpect { status { isNoContent() } }

        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect { jsonPath("$.joinRequest.status") { value("DECLINED") } }
        call(HttpMethod.GET, "/users/me", as_ = login).andExpect { status { isForbidden() } }
        val again = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))
        assertNotEquals(requestId, again["id"].asString(), "every request gets a fresh id")
        assertEquals("PENDING", again["status"].asString())
    }

    @Test
    fun `only the own stable's admin answers requests`() {
        val login = registered()
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()
        val foreignAdmin = userRepository.save(testUser(stableId = ObjectId.get()))
        stableRepository.save(testStable(id = foreignAdmin.stableId, adminUserId = foreignAdmin.id))

        call(HttpMethod.GET, "/stables/me/joinrequests", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = foreignAdmin.id).andExpect { status { isNotFound() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/decline", as_ = foreignAdmin.id).andExpect { status { isNotFound() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/${ObjectId.get().toHexString()}/accept", as_ = admin.id).andExpect { status { isNotFound() } }
        call(HttpMethod.POST, "/stables/me/joinrequests/not-an-id/accept", as_ = admin.id).andExpect { status { isBadRequest() } }
        assertEquals(JoinRequestStatus.PENDING, joinRequestRepository.requests.single().status)
    }

    @Test
    fun `accepting with a role of another stable answers 400 and leaves the request open`() {
        val login = registered()
        val foreignRole = tagRepository.save(testTag(stableId = ObjectId.get()))
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", """{"roleTagIds":["${foreignRole.id.toHexString()}"]}""", as_ = admin.id)
            .andExpect { status { isBadRequest() } }
        assertEquals(JoinRequestStatus.PENDING, joinRequestRepository.requests.single().status)
    }

    @Test
    fun `accepting a login that became a member meanwhile answers 409 and closes the request`() {
        val login = registered()
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()
        userRepository.save(testUser().copy(accountId = login))

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = admin.id).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("ALREADY_MEMBER") }
        }
        assertEquals(JoinRequestStatus.WITHDRAWN, joinRequestRepository.requests.single().status)
    }

    // Own stable

    @Test
    fun `a login creates its own stable and becomes its admin`() {
        val login = registered()

        call(HttpMethod.POST, "/accounts/me/stable", """{"name":"Reitstall Neu","place":"Bregenz"}""", as_ = login).andExpect {
            status { isCreated() }
            jsonPath("$.isAdmin") { value(true) }
            jsonPath("$.stable.name") { value("Reitstall Neu") }
            jsonPath("$.stable.place") { value("Bregenz") }
            jsonPath("$.user.email") { value("neu@hufly.test") }
            jsonPath("$.mustChangePassword") { value(false) }
        }
        call(HttpMethod.GET, "/users/me", as_ = login).andExpect { jsonPath("$.isAdmin") { value(true) } }
        call(HttpMethod.GET, "/stables/me/invitecode", as_ = login).andExpect { jsonPath("$.code") { isString() } }
    }

    @Test
    fun `creating a stable checks its fields`() {
        val login = registered()

        listOf("""{"name":" "}""", """{"name":"${"x".repeat(101)}"}""", """{"name":"Ok","place":"${"x".repeat(101)}"}""").forEach {
            call(HttpMethod.POST, "/accounts/me/stable", it, as_ = login).andExpect { status { isBadRequest() } }
        }
    }

    // Stable settings and invite code (admin)

    @Test
    fun `the admin reads and renews the invite code, the old one stops working at once`() {
        val code = json(call(HttpMethod.GET, "/stables/me/invitecode", as_ = admin.id))["code"].asString()
        assertEquals("ABCD2345", code)

        val renewed = json(call(HttpMethod.POST, "/stables/me/invitecode", as_ = admin.id))["code"].asString()

        assertNotEquals(code, renewed)
        assertEquals(renewed, json(call(HttpMethod.GET, "/stables/me/invitecode", as_ = admin.id))["code"].asString())
        val login = registered()
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login).andExpect { status { isNotFound() } }
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"$renewed"}""", as_ = login).andExpect { status { isOk() } }
    }

    @Test
    fun `members never see the invite code`() {
        call(HttpMethod.GET, "/stables/me/invitecode", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.POST, "/stables/me/invitecode", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.GET, "/users/me", as_ = admin.id).andExpect { jsonPath("$.stable.inviteCode") { doesNotExist() } }
    }

    @Test
    fun `the admin renames the stable and sets its place`() {
        call(HttpMethod.PUT, "/stables/me", """{"name":" Hof Neu ","place":"  "}""", as_ = admin.id).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Hof Neu") }
            jsonPath("$.place") { value(null) }
        }
        assertEquals("ABCD2345", stableRepository.findById(STABLE_ID)!!.inviteCode)
        call(HttpMethod.PUT, "/stables/me", """{"name":"X"}""", as_ = rider.id).andExpect { status { isForbidden() } }
        call(HttpMethod.PUT, "/stables/me", """{"name":""}""", as_ = admin.id).andExpect { status { isBadRequest() } }
    }

    // Several stables per account

    /** [login] creates [count] own stables; answers their ids in order. */
    private fun ownStables(login: ObjectId, count: Int): List<String> = (1..count).map {
        json(call(HttpMethod.POST, "/accounts/me/stable", """{"name":"Stall $it"}""", as_ = login).andExpect { status { isCreated() } })["stable"]["id"].asString()
    }

    @Test
    fun `a member asks to join another stable and creates an own one`() {
        val other = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = ObjectId.get(), name = "Anderer").copy(inviteCode = "WXYZ6789"))

        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"WXYZ6789"}""", as_ = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.stableId") { value(other.id.toHexString()) }
        }
        call(HttpMethod.POST, "/accounts/me/stable", """{"name":"Zweiter"}""", as_ = rider.id).andExpect {
            status { isCreated() }
            jsonPath("$.isAdmin") { value(true) }
            jsonPath("$.stable.name") { value("Zweiter") }
        }
        assertEquals(JoinRequestStatus.PENDING, joinRequestRepository.requests.single().status, "creating a stable keeps the open request")
    }

    @Test
    fun `the X-Stable-Id header picks the membership, without it the oldest one answers`() {
        val own = ownStables(rider.id, 1).single()

        call(HttpMethod.GET, "/users/me", as_ = rider.id).andExpect {
            jsonPath("$.stable.id") { value(STABLE_ID.toHexString()) }
            jsonPath("$.isAdmin") { value(false) }
        }
        call(HttpMethod.GET, "/users/me", as_ = rider.id, stable = own).andExpect {
            status { isOk() }
            jsonPath("$.stable.id") { value(own) }
            jsonPath("$.isAdmin") { value(true) }
        }
        call(HttpMethod.GET, "/stables/me/invitecode", as_ = rider.id, stable = own).andExpect { status { isOk() } }
        call(HttpMethod.GET, "/stables/me/invitecode", as_ = rider.id, stable = STABLE_ID.toHexString()).andExpect { status { isForbidden() } }
    }

    @Test
    fun `a malformed X-Stable-Id answers 400, a stable the account is not in answers 403 NO_STABLE`() {
        call(HttpMethod.GET, "/users/me", as_ = rider.id, stable = "not-an-id").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/users/me", as_ = rider.id, stable = ObjectId.get().toHexString()).andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NO_STABLE") }
        }
    }

    @Test
    fun `account-level endpoints ignore the X-Stable-Id header`() {
        val login = registered()

        call(HttpMethod.GET, "/accounts/me", as_ = login, stable = ObjectId.get().toHexString()).andExpect { status { isOk() } }
        call(HttpMethod.GET, "/users/me/settings", as_ = login, stable = "not-an-id").andExpect { status { isOk() } }
    }

    @Test
    fun `the account lists its memberships, oldest first`() {
        val own = ownStables(rider.id, 1).single()

        call(HttpMethod.GET, "/accounts/me", as_ = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.memberships.length()") { value(2) }
            jsonPath("$.memberships[0].userId") { value(rider.id.toHexString()) }
            jsonPath("$.memberships[0].stableId") { value(STABLE_ID.toHexString()) }
            jsonPath("$.memberships[0].stableName") { value("Hof Lerchenfeld") }
            jsonPath("$.memberships[0].stablePlace") { value("Dornbirn") }
            jsonPath("$.memberships[0].isAdmin") { value(false) }
            jsonPath("$.memberships[0].createdAt") { value(0) }
            jsonPath("$.memberships[1].stableId") { value(own) }
            jsonPath("$.memberships[1].stableName") { value("Stall 1") }
            jsonPath("$.memberships[1].isAdmin") { value(true) }
        }
        call(HttpMethod.GET, "/accounts/me", as_ = registered()).andExpect { jsonPath("$.memberships.length()") { value(0) } }
    }

    @Test
    fun `an account has at most 10 stables`() {
        val login = registered()
        ownStables(login, 10)

        call(HttpMethod.POST, "/accounts/me/stable", """{"name":"Elfter"}""", as_ = login).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STABLE_LIMIT") }
        }
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STABLE_LIMIT") }
        }
    }

    @Test
    fun `accepting a login that reached the limit meanwhile answers 409 STABLE_LIMIT and closes the request`() {
        val login = registered()
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()
        ownStables(login, 10)

        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = admin.id).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STABLE_LIMIT") }
        }
        assertEquals(JoinRequestStatus.WITHDRAWN, joinRequestRepository.requests.single().status)
    }

    @Test
    fun `deleting the account leaves every stable, the admin of a stable with others must stay`() {
        val password = """{"password":"Secret123"}"""
        val login = registered()
        val requestId = json(call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login))["id"].asString()
        call(HttpMethod.POST, "/stables/me/joinrequests/$requestId/accept", as_ = admin.id).andExpect { status { isOk() } }
        val own = ObjectId(ownStables(login, 1).single())
        val guest = userRepository.save(testUser(stableId = own))

        call(HttpMethod.DELETE, "/users/me", password, as_ = login).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STABLE_ADMIN") }
        }
        assertTrue(userRepository.findByAccountIdAndDeletedFalse(login).size == 2, "nothing changes on 409")

        userRepository.save(guest.copy(deleted = true))
        call(HttpMethod.DELETE, "/users/me", password, as_ = login).andExpect { status { isNoContent() } }

        assertTrue(userRepository.findByAccountIdAndDeletedFalse(login).isEmpty())
        assertTrue(stableRepository.findById(own)?.deleted ?: true, "the own stable without others goes")
        assertTrue(stableRepository.findById(STABLE_ID)!!.deleted.not())
        assertTrue(userRepository.accounts.findById(login)!!.deleted)
    }

    // Account deletion without a stable

    @Test
    fun `a login without a stable deletes itself`() {
        val login = registered()
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""", as_ = login)

        call(HttpMethod.DELETE, "/users/me", """{"password":"Secret123"}""", as_ = login).andExpect { status { isNoContent() } }

        assertTrue(userRepository.accounts.findById(login)!!.deleted)
        assertNull(userRepository.accounts.findByEmail("neu@hufly.test"))
        assertEquals(JoinRequestStatus.WITHDRAWN, joinRequestRepository.requests.single().status)
        call(HttpMethod.GET, "/accounts/me", as_ = login).andExpect { status { isUnauthorized() } }
        registered("neu@hufly.test")
    }

    @Test
    fun `unauthenticated calls answer 401`() {
        call(HttpMethod.GET, "/accounts/me").andExpect { status { isUnauthorized() } }
        call(HttpMethod.PUT, "/accounts/me/joinrequest", """{"code":"ABCD2345"}""").andExpect { status { isUnauthorized() } }
        call(HttpMethod.GET, "/stables/me/invitecode").andExpect { status { isUnauthorized() } }
    }
}
