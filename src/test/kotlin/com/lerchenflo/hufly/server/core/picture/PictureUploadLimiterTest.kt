package com.lerchenflo.hufly.server.core.picture

import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.security.TooManyAttemptsException
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.testdata.seconds
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PictureUploadLimiterTest {

    private val clock = MutableClock()
    private val limiter = PictureUploadLimiter(maxPerMinute = 4, clock = clock)
    private val anna = ObjectId()
    private val berta = ObjectId()

    @Test
    fun `allows four uploads per account and minute`() {
        repeat(4) { limiter.upload(anna) }

        val blocked = assertFailsWith<TooManyAttemptsException> { limiter.upload(anna) }

        assertEquals("60", blocked.headers.getFirst("Retry-After"))
        limiter.upload(berta)
    }

    @Test
    fun `uploads fall out of the window after a minute`() {
        repeat(4) { limiter.upload(anna) }
        clock.advance(seconds(59))
        assertFailsWith<TooManyAttemptsException> { limiter.upload(anna) }

        clock.advance(seconds(1))

        limiter.upload(anna)
    }

    @Test
    fun `blocked attempts do not extend the block`() {
        repeat(4) { limiter.upload(anna) }
        repeat(10) { assertFailsWith<TooManyAttemptsException> { limiter.upload(anna) } }

        clock.advance(minutes(1))

        limiter.upload(anna)
    }
}
