package com.lerchenflo.hufly.server.core.picture

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_MINUTE
import com.lerchenflo.hufly.server.core.security.FailureLimiter
import com.lerchenflo.hufly.server.core.security.TooManyAttemptsException
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Decoding an upload can take ~160 MB of heap, so every allowed attempt counts per account, broken ones too. */
@Component
class PictureUploadLimiter(
    @Value("\${ratelimit.picture-upload.max-per-minute:4}") maxPerMinute: Int,
    clock: Clock,
) {
    private val uploads = FailureLimiter(maxPerMinute, MILLIS_PER_MINUTE, clock)

    fun upload(accountId: ObjectId) {
        val key = accountId.toHexString()
        uploads.retryAfter(key)?.let { throw TooManyAttemptsException(it) }
        uploads.recordFailure(key)
    }
}
