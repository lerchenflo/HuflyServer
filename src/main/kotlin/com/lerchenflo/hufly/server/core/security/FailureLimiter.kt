package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_SECOND
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts failures per key in a sliding window, in memory. Like the version counter this assumes a single server
 * instance; scaling out needs a shared store (SchneaggchatV3server uses bucket4j with Redis).
 */
class FailureLimiter(
    private val maxFailures: Int,
    /** In milliseconds. */
    private val window: Long,
    private val clock: Clock,
) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun recordFailure(key: String) {
        val now = clock.millis()
        val times = failures.computeIfAbsent(key) { ArrayDeque() }
        synchronized(times) {
            times.prune(now)
            times.addLast(now)
        }
        if (failures.size > MAX_TRACKED_KEYS) failures.entries.removeIf { (_, times) -> synchronized(times) { times.prune(now); times.isEmpty() } }
    }

    /** Milliseconds [key] stays blocked, or null if it may try now. */
    fun retryAfter(key: String): Long? {
        val now = clock.millis()
        val times = failures[key] ?: return null
        synchronized(times) {
            times.prune(now)
            if (times.size < maxFailures) return null
            return times[times.size - maxFailures] + window - now
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    private fun ArrayDeque<Long>.prune(now: Long) {
        while (isNotEmpty() && first() + window <= now) removeFirst()
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000
    }
}

/** 429 with a Retry-After header in whole seconds. */
class TooManyAttemptsException(retryAfter: Long) : ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts") {
    private val seconds = retryAfterSeconds(retryAfter)

    override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, seconds.toString()) }
}

/** Whole seconds for a Retry-After header, at least 1. */
fun retryAfterSeconds(retryAfterMillis: Long): Long = (retryAfterMillis / MILLIS_PER_SECOND).coerceAtLeast(1)
