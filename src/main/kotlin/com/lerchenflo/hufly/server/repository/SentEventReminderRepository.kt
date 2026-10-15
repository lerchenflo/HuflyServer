package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.SentEventReminder
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface SentEventReminderRepository : Repository<SentEventReminder, ObjectId> {
    /** Fails with a duplicate key error if that user was reminded of that start already. */
    fun insert(reminder: SentEventReminder): SentEventReminder
    fun findByStartAtGreaterThan(time: Long): List<SentEventReminder>
    fun deleteByStartAtBefore(time: Long): Long
    fun deleteByStableId(stableId: ObjectId): Long
}
