package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

/** USR-7: `loginManagedByStable` follows the same rule as the admin's login rights. */
class UserResponsesTest {

    private val userRepository = FakeUserRepository()
    private val userResponses = UserResponses(userRepository.accounts, userRepository)

    @Test
    fun `a stable-created login counts as managed only while it has no other stable`() {
        val kid = userRepository.save(testUser(email = "kid@hufly.test"))
        val rider = userRepository.save(testUser(email = "rider@hufly.test"))
        userRepository.save(testUser(email = "rider@hufly.test", stableId = OTHER_STABLE_ID).copy(accountId = rider.accountId))

        assertEquals(true, userResponses.of(kid).loginManagedByStable)
        assertEquals(false, userResponses.of(rider).loginManagedByStable)
        assertEquals(listOf(true, false), userResponses.of(listOf(kid, rider)).let { listOf(it[kid]!!, it[rider]!!) }.map { it.loginManagedByStable })
    }
}
