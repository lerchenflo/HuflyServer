package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.SentReminder
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

class FakeSentReminderRepository : SentReminderRepository {
    val reminders = mutableListOf<SentReminder>()

    override fun insert(reminder: SentReminder): SentReminder {
        if (reminders.any { it.taskId == reminder.taskId && it.dueAt == reminder.dueAt && it.userId == reminder.userId }) {
            throw DuplicateKeyException("task_due_user")
        }
        reminders += reminder
        return reminder
    }

    override fun findByDueAtGreaterThan(time: Long): List<SentReminder> = reminders.filter { it.dueAt > time }

    override fun deleteByDueAtBefore(time: Long): Long = reminders.count { it.dueAt < time }.toLong().also { reminders.removeIf { it.dueAt < time } }

    override fun deleteByStableId(stableId: ObjectId): Long =
        reminders.count { it.stableId == stableId }.toLong().also { reminders.removeIf { it.stableId == stableId } }
}
