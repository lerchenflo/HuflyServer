package com.lerchenflo.hufly.server.core

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * All times are plain Longs: instants as epoch milliseconds (UTC), calendar dates as epoch days (days since
 * 1970-01-01, negative before). They are stored and sent like that; only zone-aware calendar math uses kotlinx-datetime.
 */
const val MILLIS_PER_SECOND = 1_000L
const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR

/** Upper bound for epoch-millisecond request fields (3000-01-01T00:00:00Z). */
const val MAX_EPOCH_MILLIS = 32503680000000L

/** Bound for epoch-day request fields in both directions (about 1000 years around 1970). */
const val MAX_EPOCH_DAYS = MAX_EPOCH_MILLIS / MILLIS_PER_DAY

/** The current time in epoch milliseconds; inject it instead of calling `System.currentTimeMillis()`. */
fun interface Clock {
    fun millis(): Long
}

/** An epoch-millisecond path or body value; 400 outside 0..[MAX_EPOCH_MILLIS]. */
fun requireEpochMillis(millis: Long): Long {
    if (millis !in 0..MAX_EPOCH_MILLIS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Time out of range")
    return millis
}

/** An epoch-day body value; 400 outside -[MAX_EPOCH_DAYS]..[MAX_EPOCH_DAYS]. */
fun requireEpochDay(day: Long): Long {
    if (day !in -MAX_EPOCH_DAYS..MAX_EPOCH_DAYS) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date out of range")
    return day
}
