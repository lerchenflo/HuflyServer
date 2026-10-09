package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeJoinRequestRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BIZ-1, BIZ-2: a new stable always comes with exactly one admin; self-signup creates one for an existing login. */
class StableOnboardingServiceTest {

    private val clock = MutableClock()
    private val hashEncoder = HashEncoder()
    private val userRepository = FakeUserRepository()
    private val accounts = userRepository.accounts
    private val stableRepository = FakeStableRepository()
    private val joinRequests = FakeJoinRequestRepository()
    private val accessService = AccessService(userRepository, accounts, stableRepository, FakeTagRepository())
    private val onboarding = StableOnboardingService(userRepository, accounts, stableRepository, joinRequests, hashEncoder, clock)

    @Test
    fun `creates a stable with its admin and a login for them`() {
        val created = onboarding.createStable("Hof Lerchenfeld", " Chef@Hufly.test ", "Chef", "Secret123")

        val login = accounts.findByEmail("chef@hufly.test")!!
        val admin = userRepository.findFirstByAccountIdAndDeletedFalse(login.id)!!
        assertEquals(created.stable.id, admin.stableId)
        assertEquals(admin.id, created.stable.adminUserId)
        assertEquals("Chef", admin.displayName)
        assertEquals("chef@hufly.test", admin.email)
        assertEquals(clock.millis(), created.stable.createdAt)
        assertTrue(hashEncoder.matches("Secret123", login.hashedPassword))
        assertEquals(created.stable.id, login.createdByStableId)
        assertTrue(accessService.isAdmin(admin))
    }

    @Test
    fun `the new admin must choose an own password, like members the admin creates`() {
        onboarding.createStable("Hof Lerchenfeld", "chef@hufly.test", "Chef", "Secret123")

        assertTrue(accounts.findByEmail("chef@hufly.test")!!.mustChangePassword)
    }

    @Test
    fun `every new stable gets its own invite code`() {
        val first = onboarding.createStable("A", "a@hufly.test", "A", "Secret123").stable
        val second = onboarding.createStable("B", "b@hufly.test", "B", "Secret123").stable

        assertNotNull(InviteCodes.normalize(first.inviteCode!!))
        assertTrue(first.inviteCode != second.inviteCode)
    }

    @Test
    fun `an email used in any stable conflicts`() {
        userRepository.save(testUser(email = "chef@hufly.test"))

        val error = assertFailsWith<CodedException> { onboarding.createStable("X", "chef@hufly.test", "Chef", "Secret123") }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals("EMAIL_IN_USE", error.code)
        assertTrue(stableRepository.stables.isEmpty())
    }

    // Self-service

    @Test
    fun `a registered account creates its own stable and becomes its admin`() {
        val login = accounts.save(testAccount(email = "anna@hufly.test"))

        val created = onboarding.createStableForAccount(login, " Reitstall Anna ", " Dornbirn ")

        assertEquals("Reitstall Anna", created.stable.name)
        assertEquals("Dornbirn", created.stable.place)
        assertEquals(login.id, created.admin.accountId)
        assertEquals("anna@hufly.test", created.admin.email)
        assertTrue(accessService.isAdmin(created.admin))
        assertEquals(created.admin, accessService.requester(login.id))
        assertTrue(!accounts.findById(login.id)!!.mustChangePassword, "the account chose its password itself")
    }

    @Test
    fun `a blank place is stored as none`() {
        val login = accounts.save(testAccount())

        assertNull(onboarding.createStableForAccount(login, "Stall", "  ").stable.place)
    }

    @Test
    fun `an account with a stable cannot create a second one`() {
        val login = accounts.save(testAccount())
        onboarding.createStableForAccount(login, "Erster", null)

        val error = assertFailsWith<CodedException> { onboarding.createStableForAccount(login, "Zweiter", null) }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals("ALREADY_MEMBER", error.code)
        assertEquals(listOf("Erster"), stableRepository.stables.map { it.name })
    }

    @Test
    fun `creating an own stable withdraws an open join request`() {
        val login = accounts.save(testAccount())
        val request = joinRequests.save(
            JoinRequest(accountId = login.id, stableId = org.bson.types.ObjectId.get(), status = JoinRequestStatus.PENDING, createdAt = 0L, updatedAt = 0L)
        )

        onboarding.createStableForAccount(login, "Stall", null)

        assertEquals(JoinRequestStatus.WITHDRAWN, joinRequests.findById(request.id)!!.status)
    }
}
