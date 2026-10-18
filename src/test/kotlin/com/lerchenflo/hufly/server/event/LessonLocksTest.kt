package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.event.model.LessonLock
import com.lerchenflo.hufly.server.repository.FakeLessonLockRepository
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LessonLocksTest {

    private val clock = MutableClock()
    private val repository = FakeLessonLockRepository()
    private val locks = LessonLocks(repository, clock, waitMillis = 50)
    private val eventId = ObjectId.get()

    private fun heldByOther(until: Long) {
        repository.insert(LessonLock(eventId, STABLE_ID, ObjectId.get(), until))
    }

    @Test
    fun `the lock is held while the block runs and goes afterwards, also when it fails`() {
        val held = locks.holding(eventId, STABLE_ID) { repository.locks.keys.toList() }
        assertFailsWith<IllegalStateException> { locks.holding(eventId, STABLE_ID) { error("refused") } }

        assertEquals(listOf(eventId), held)
        assertEquals(emptyMap(), repository.locks)
    }

    @Test
    fun `a lock held by another request answers 503 after waiting, and stays theirs`() {
        heldByOther(until = clock.millis() + 1)

        val e = assertFailsWith<ResponseStatusException> { locks.holding(eventId, STABLE_ID) { } }

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE to "Lesson: busy, try again", e.statusCode to e.reason)
        assertEquals(1, repository.locks.size)
    }

    @Test
    fun `a lock left behind past its lease is taken over`() {
        heldByOther(until = clock.millis() - 1)

        assertEquals("ran", locks.holding(eventId, STABLE_ID) { "ran" })
        assertEquals(emptyMap(), repository.locks)
    }

    @Test
    fun `a lease lasts LEASE_MILLIS`() {
        val until = locks.holding(eventId, STABLE_ID) { repository.locks.getValue(eventId).until }

        assertEquals(clock.millis() + LessonLocks.LEASE_MILLIS, until)
    }
}
