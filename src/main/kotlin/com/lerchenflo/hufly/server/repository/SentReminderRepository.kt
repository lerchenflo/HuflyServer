package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.SentReminder
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface SentReminderRepository : Repository<SentReminder, ObjectId> {
    /** Fails with a duplicate key error if that user heard about that due time already. */
    fun insert(reminder: SentReminder): SentReminder
    fun findByDueAtGreaterThan(time: Long): List<SentReminder>
    fun deleteByDueAtBefore(time: Long): Long
    fun deleteByStableId(stableId: ObjectId): Long
}
