package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BIZ-1, BIZ-2: a new stable always comes with exactly one admin. */
class StableOnboardingServiceTest {

    private val clock = MutableClock()
    private val hashEncoder = HashEncoder()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val accessService = AccessService(userRepository, stableRepository, FakeTagRepository())
    private val onboarding = StableOnboardingService(userRepository, stableRepository, hashEncoder, clock)

    @Test
    fun `creates a stable with its admin`() {
        val created = onboarding.createStable("Hof Lerchenfeld", " Chef@Hufly.test ", "Chef", "Secret123")

        val admin = userRepository.findByEmail("chef@hufly.test")!!
        assertEquals(created.stable.id, admin.stableId)
        assertEquals(admin.id, created.stable.adminUserId)
        assertEquals("Chef", admin.displayName)
        assertEquals(clock.instant(), created.stable.createdAt)
        assertTrue(hashEncoder.matches("Secret123", admin.hashedPassword))
        assertTrue(accessService.isAdmin(admin))
    }

    @Test
    fun `an email used in any stable conflicts`() {
        userRepository.save(testUser(email = "chef@hufly.test"))

        val error = assertFailsWith<ResponseStatusException> { onboarding.createStable("X", "chef@hufly.test", "Chef", "Secret123") }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertTrue(stableRepository.stables.isEmpty())
    }
}
