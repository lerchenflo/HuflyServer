package com.lerchenflo.hufly.server.core.sync

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Stamps writes of growing collections with increasing versions and tells sync how far it may read.
 *
 * A write can get version N and still be saving while N+1 is already saved. Sync must not read past N-1 then, or
 * the client advances beyond N and never gets it. Counting and registering a version happen under one lock, and so
 * does reading the watermark, so no version is ever counted but not yet registered while sync looks.
 * In-flight versions live in memory: the server must run as a single instance.
 */
@Service
class VersionCounterService(private val store: VersionCounterStore) {

    private val inFlight = ConcurrentHashMap<SyncCollection, ConcurrentSkipListSet<Long>>()
    private val locks = ConcurrentHashMap<SyncCollection, Any>()

    /** Run the save in [block]; the version counts as in progress until it returns or throws. */
    fun <T> withVersion(collection: SyncCollection, block: (Long) -> T): T {
        val versions = inFlightFor(collection)
        val version = synchronized(lockFor(collection)) {
            store.increment(collection).also { versions += it }
        }
        try {
            return block(version)
        } finally {
            versions -= version
        }
    }

    /** Highest version below every write still in progress. */
    fun safeWatermark(collection: SyncCollection): Long = synchronized(lockFor(collection)) {
        val lowest = inFlightFor(collection).firstOrNull()
        if (lowest != null) lowest - 1 else store.current(collection)
    }

    private fun inFlightFor(collection: SyncCollection) = inFlight.computeIfAbsent(collection) { ConcurrentSkipListSet() }

    private fun lockFor(collection: SyncCollection) = locks.computeIfAbsent(collection) { Any() }
}
