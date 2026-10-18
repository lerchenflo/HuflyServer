package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.event.model.LessonLock
import com.lerchenflo.hufly.server.repository.LessonLockRepository
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * One request at a time checks and changes a lesson's places, so two cannot take the last place together: [holding]
 * keeps the event's row in `lessonLocks` (an insert, or the atomic take-over of a row whose holder died) and waits for
 * it otherwise. In Mongo, so it holds across server instances too.
 */
@Component
class LessonLocks(
    private val repository: LessonLockRepository,
    private val clock: Clock,
    private val waitMillis: Long = WAIT_MILLIS,
) {
    fun <T> holding(eventId: ObjectId, stableId: ObjectId, block: () -> T): T {
        val token = ObjectId.get()
        var waited = 0L
        while (!take(eventId, stableId, token)) {
            if (waited >= waitMillis) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lesson: busy, try again")
            Thread.sleep(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        try {
            return block()
        } finally {
            repository.deleteByEventIdAndToken(eventId, token)
        }
    }

    private fun take(eventId: ObjectId, stableId: ObjectId, token: ObjectId): Boolean {
        val now = clock.millis()
        return try {
            repository.insert(LessonLock(eventId, stableId, token, now + LEASE_MILLIS))
            true
        } catch (_: DuplicateKeyException) {
            repository.takeExpired(eventId, now, token, now + LEASE_MILLIS) == 1L
        }
    }

    companion object {
        /** A holder that died blocks its lesson this long. */
        const val LEASE_MILLIS = 30_000L
        const val WAIT_MILLIS = 5_000L
        private const val POLL_MILLIS = 10L
    }
}
