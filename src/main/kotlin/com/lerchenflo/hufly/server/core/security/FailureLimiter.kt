package com.lerchenflo.hufly.server.core.security

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts failures per key in a sliding window, in memory. Like the version counter this assumes a single server
 * instance; scaling out needs a shared store (SchneaggchatV3server uses bucket4j with Redis).
 */
class FailureLimiter(
    private val maxFailures: Int,
    private val window: Duration,
    private val clock: Clock,
) {
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun recordFailure(key: String) {
        val now = clock.instant()
        val times = failures.computeIfAbsent(key) { ArrayDeque() }
        synchronized(times) {
            times.prune(now)
            times.addLast(now)
        }
        if (failures.size > MAX_TRACKED_KEYS) failures.entries.removeIf { (_, times) -> synchronized(times) { times.prune(now); times.isEmpty() } }
    }

    /** How long [key] stays blocked, or null if it may try now. */
    fun retryAfter(key: String): Duration? {
        val now = clock.instant()
        val times = failures[key] ?: return null
        synchronized(times) {
            times.prune(now)
            if (times.size < maxFailures) return null
            return Duration.between(now, times[times.size - maxFailures].plus(window))
        }
    }

    fun reset(key: String) {
        failures.remove(key)
    }

    private fun ArrayDeque<Instant>.prune(now: Instant) {
        while (isNotEmpty() && !first().plus(window).isAfter(now)) removeFirst()
    }

    private companion object {
        const val MAX_TRACKED_KEYS = 100_000
    }
}

/** 429 with a Retry-After header in whole seconds. */
class TooManyAttemptsException(retryAfter: Duration) : ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts") {
    private val seconds = retryAfter.toSeconds().coerceAtLeast(1)

    override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, seconds.toString()) }
}
