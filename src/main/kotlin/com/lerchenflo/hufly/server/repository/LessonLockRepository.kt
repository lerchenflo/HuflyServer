package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.LessonLock
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository

interface LessonLockRepository : Repository<LessonLock, ObjectId> {
    /** Fails with a duplicate key error while the event's lock is held. */
    fun insert(lock: LessonLock): LessonLock

    /** Atomic: answers 1 only for the one caller that takes over the lock of [eventId] whose lease ended before [now]. */
    @Query("{ '_id': ?0, 'until': { '\$lt': ?1 } }")
    @Update("{ '\$set': { 'token': ?2, 'until': ?3 } }")
    fun takeExpired(eventId: ObjectId, now: Long, token: ObjectId, until: Long): Long

    /** Lets go of the lock only while [token] still holds it. */
    fun deleteByEventIdAndToken(eventId: ObjectId, token: ObjectId): Long
    fun deleteByStableId(stableId: ObjectId): Long
}
