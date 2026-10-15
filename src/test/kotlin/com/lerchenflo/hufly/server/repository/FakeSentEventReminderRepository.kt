package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.SentEventReminder
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

class FakeSentEventReminderRepository : SentEventReminderRepository {
    val reminders = mutableListOf<SentEventReminder>()

    override fun insert(reminder: SentEventReminder): SentEventReminder {
        if (reminders.any { it.eventId == reminder.eventId && it.startAt == reminder.startAt && it.userId == reminder.userId }) {
            throw DuplicateKeyException("event_start_user")
        }
        reminders += reminder
        return reminder
    }

    override fun findByStartAtGreaterThan(time: Long): List<SentEventReminder> = reminders.filter { it.startAt > time }

    override fun deleteByStartAtBefore(time: Long): Long =
        reminders.count { it.startAt < time }.toLong().also { reminders.removeIf { it.startAt < time } }

    override fun deleteByStableId(stableId: ObjectId): Long =
        reminders.count { it.stableId == stableId }.toLong().also { reminders.removeIf { it.stableId == stableId } }
}
