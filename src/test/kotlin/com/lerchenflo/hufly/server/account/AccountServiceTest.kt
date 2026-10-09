package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.core.notification.JoinRequested
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.LoginGuard
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeJoinRequestRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.stable.StableOnboardingService
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testStable
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.Test
import kotlin.test.assertEquals

/** The admin hears about a join request by push, but asking again soon (e.g. after a decline) does not spam them. */
class AccountServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val joinRequests = FakeJoinRequestRepository()
    private val published = mutableListOf<Any>()
    private val service = AccountService(
        userRepository, stableRepository, joinRequests,
        StableOnboardingService(userRepository, userRepository.accounts, stableRepository, joinRequests, HashEncoder(), clock),
        LoginGuard(1000, 1000, 1000, 1000, 1000, clock),
        ApplicationEventPublisher { published += it },
        clock,
    )

    private val login = userRepository.accounts.save(testAccount())

    init {
        stableRepository.save(testStable(adminUserId = ObjectId.get()).copy(inviteCode = "ABCD2345"))
        stableRepository.save(testStable(id = ObjectId.get(), adminUserId = ObjectId.get()).copy(inviteCode = "WXYZ6789"))
    }

    private fun pushes() = published.filterIsInstance<JoinRequested>().map { it.stableId }

    @Test
    fun `asking the same stable again within a day pushes only once`() {
        service.requestToJoin(login, "ABCD2345")
        clock.advance(hours(23))
        service.requestToJoin(login, "ABCD2345")

        assertEquals(1, pushes().size)
    }

    @Test
    fun `another stable or a day later pushes again`() {
        service.requestToJoin(login, "ABCD2345")
        service.requestToJoin(login, "WXYZ6789")
        clock.advance(days(1) + 1)
        service.requestToJoin(login, "ABCD2345")

        assertEquals(3, pushes().size)
    }
}
