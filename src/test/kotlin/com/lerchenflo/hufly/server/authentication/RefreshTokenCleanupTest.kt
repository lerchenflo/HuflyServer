package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.minutes
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Times are stored as Long, so Mongo's TTL index cannot expire sessions; this job does. */
class RefreshTokenCleanupTest {

    private val clock = MutableClock()
    private val repository = FakeRefreshTokenRepository()
    private val cleanup = RefreshTokenCleanup(repository, clock)

    @Test
    fun `removes expired sessions and keeps live ones`() {
        repository.save(RefreshToken(userId = ObjectId.get(), hashedToken = "old", expiresAt = clock.millis() - minutes(1), createdAt = 0L))
        repository.save(RefreshToken(userId = ObjectId.get(), hashedToken = "live", expiresAt = clock.millis() + days(1), createdAt = 0L))

        cleanup.removeExpiredSessions()

        assertEquals(listOf("live"), repository.tokens.map { it.hashedToken })
    }
}
