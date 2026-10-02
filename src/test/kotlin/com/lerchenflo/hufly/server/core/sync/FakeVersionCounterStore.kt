package com.lerchenflo.hufly.server.core.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory counters. [incrementDelayMillis] widens the window between counting and returning, like a Mongo round trip. */
class FakeVersionCounterStore(private val incrementDelayMillis: Long = 0) : VersionCounterStore {
    private val counters = ConcurrentHashMap<SyncCollection, AtomicLong>()

    override fun increment(collection: SyncCollection): Long {
        val version = counters.computeIfAbsent(collection) { AtomicLong() }.incrementAndGet()
        if (incrementDelayMillis > 0) Thread.sleep(incrementDelayMillis)
        return version
    }

    override fun current(collection: SyncCollection): Long = counters[collection]?.get() ?: 0
}
