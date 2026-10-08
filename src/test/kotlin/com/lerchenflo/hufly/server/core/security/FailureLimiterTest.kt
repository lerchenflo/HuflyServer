package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.testdata.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailureLimiterTest {

    private val clock = MutableClock()
    private val limiter = FailureLimiter(maxFailures = 3, window = minutes(15), clock = clock)

    @Test
    fun `allows up to the limit and blocks after it`() {
        repeat(2) { limiter.recordFailure("anna") }
        assertNull(limiter.retryAfter("anna"))

        limiter.recordFailure("anna")

        assertEquals(minutes(15), limiter.retryAfter("anna"))
    }

    @Test
    fun `old failures fall out of the window`() {
        repeat(3) { limiter.recordFailure("anna") }
        clock.advance(minutes(10))
        assertEquals(minutes(5), limiter.retryAfter("anna"))

        clock.advance(minutes(5))

        assertNull(limiter.retryAfter("anna"))
    }

    @Test
    fun `keys are independent and reset clears one`() {
        repeat(3) { limiter.recordFailure("anna") }
        repeat(3) { limiter.recordFailure("ben") }

        limiter.reset("anna")

        assertNull(limiter.retryAfter("anna"))
        assertEquals(minutes(15), limiter.retryAfter("ben"))
    }
}
