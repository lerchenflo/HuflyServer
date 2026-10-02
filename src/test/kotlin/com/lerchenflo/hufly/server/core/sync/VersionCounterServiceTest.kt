package com.lerchenflo.hufly.server.core.sync

import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** OFF-2: version sync must never skip a write that is still in progress. */
class VersionCounterServiceTest {

    private val service = VersionCounterService(FakeVersionCounterStore())

    @Test
    fun `versions increase per collection`() {
        assertEquals(1, service.withVersion(SyncCollection.EVENTS) { it })
        assertEquals(2, service.withVersion(SyncCollection.EVENTS) { it })
        assertEquals(1, service.withVersion(SyncCollection.TASKS) { it })
    }

    @Test
    fun `watermark is the counter when nothing is in progress`() {
        repeat(3) { service.withVersion(SyncCollection.EVENTS) { } }

        assertEquals(3, service.safeWatermark(SyncCollection.EVENTS))
    }

    @Test
    fun `watermark stays below a write in progress`() {
        service.withVersion(SyncCollection.EVENTS) { }

        service.withVersion(SyncCollection.EVENTS) { version ->
            assertEquals(2, version)
            assertEquals(1, service.safeWatermark(SyncCollection.EVENTS))
        }
        assertEquals(2, service.safeWatermark(SyncCollection.EVENTS))
    }

    @Test
    fun `failed write frees the watermark`() {
        assertFailsWith<IllegalStateException> { service.withVersion(SyncCollection.EVENTS) { error("save failed") } }

        assertEquals(1, service.safeWatermark(SyncCollection.EVENTS))
    }

    @Test
    fun `concurrent writes are never below a watermark before they finish`() {
        val slowService = VersionCounterService(FakeVersionCounterStore(incrementDelayMillis = 1))
        val finished = Collections.synchronizedSet(mutableSetOf<Long>())
        val violations = Collections.synchronizedList(mutableListOf<String>())
        val running = AtomicBoolean(true)
        val pool = Executors.newFixedThreadPool(9)

        repeat(8) {
            pool.submit {
                repeat(40) {
                    slowService.withVersion(SyncCollection.EVENTS) { version ->
                        Thread.sleep(Random.nextLong(0, 3))
                        finished += version
                    }
                }
            }
        }
        pool.submit {
            while (running.get()) {
                val watermark = slowService.safeWatermark(SyncCollection.EVENTS)
                val missing = (1..watermark).filter { it !in finished }
                if (missing.isNotEmpty()) violations += "watermark $watermark, unfinished $missing"
            }
        }
        pool.shutdown()
        Thread.sleep(50)
        while (finished.size < 320) Thread.sleep(10)
        running.set(false)
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        assertEquals(emptyList(), violations)
        assertEquals(320, slowService.safeWatermark(SyncCollection.EVENTS))
    }
}
