package com.lerchenflo.hufly.server.core

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/** Upper bound for epoch-millisecond request fields (3000-01-01T00:00:00Z); larger values would overflow Instant. */
const val MAX_EPOCH_MILLIS = 32503680000000L

/** An epoch-millisecond path or body value as Instant; 400 outside 0..[MAX_EPOCH_MILLIS]. */
fun epochMillisToInstant(millis: Long): Instant {
    if (millis !in 0..MAX_EPOCH_MILLIS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Time out of range")
    return Instant.ofEpochMilli(millis)
}
