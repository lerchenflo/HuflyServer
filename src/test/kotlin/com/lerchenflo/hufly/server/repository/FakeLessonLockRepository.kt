package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.LessonLock
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

/** Thread-safe, since tests let requests race for a lock. */
class FakeLessonLockRepository : LessonLockRepository {
    val locks = mutableMapOf<ObjectId, LessonLock>()

    @Synchronized
    override fun insert(lock: LessonLock): LessonLock {
        if (lock.eventId in locks) throw DuplicateKeyException("_id")
        locks[lock.eventId] = lock
        return lock
    }

    @Synchronized
    override fun takeExpired(eventId: ObjectId, now: Long, token: ObjectId, until: Long): Long {
        val lock = locks[eventId]?.takeIf { it.until < now } ?: return 0
        locks[eventId] = lock.copy(token = token, until = until)
        return 1
    }

    @Synchronized
    override fun deleteByEventIdAndToken(eventId: ObjectId, token: ObjectId): Long =
        if (locks[eventId]?.token == token) 1L.also { locks.remove(eventId) } else 0

    @Synchronized
    override fun deleteByStableId(stableId: ObjectId): Long =
        locks.values.count { it.stableId == stableId }.toLong().also { locks.values.removeIf { it.stableId == stableId } }
}
