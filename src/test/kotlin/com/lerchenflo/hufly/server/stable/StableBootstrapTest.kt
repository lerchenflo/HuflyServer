package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Local first stable until the onboarding website exists (BIZ-1, BIZ-2). */
class StableBootstrapTest {

    private val clock = MutableClock()
    private val hashEncoder = HashEncoder()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val accessService = AccessService(userRepository, stableRepository, FakeTagRepository())
    private val bootstrap = StableBootstrap(userRepository, stableRepository, hashEncoder, clock)

    @Test
    fun `creates the stable with its admin when the email is unknown`() {
        bootstrap.run("Teststall", " Admin@Hufly.test ", "Secret123")

        val admin = userRepository.findByEmail("admin@hufly.test")!!
        val stable = stableRepository.findById(admin.stableId)!!
        assertEquals("Teststall", stable.name)
        assertEquals(admin.id, stable.adminUserId)
        assertEquals(SubscriptionStatus.TRIAL, stable.subscriptionStatus)
        assertTrue(hashEncoder.matches("Secret123", admin.hashedPassword))
        assertTrue(accessService.isAdmin(admin))
    }

    @Test
    fun `does nothing when the email already exists`() {
        bootstrap.run("Teststall", "admin@hufly.test", "Secret123")

        bootstrap.run("Anderer Stall", "admin@hufly.test", "Other123")

        assertEquals(1, stableRepository.stables.size)
        assertEquals(1, userRepository.users.size)
    }

    @Test
    fun `does nothing unless all three values are set`() {
        bootstrap.run("", "admin@hufly.test", "Secret123")
        bootstrap.run("Teststall", "", "Secret123")
        bootstrap.run("Teststall", "admin@hufly.test", "")

        assertTrue(stableRepository.stables.isEmpty())
        assertTrue(userRepository.users.isEmpty())
    }
}
