package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.SentReminder
import com.lerchenflo.hufly.server.repository.SentReminderRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.task.model.rotationAssignee
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Reminds the effective assignees of open tasks and series dates `taskReminderMinutes` before they are due, at once
 * also for digest users, skipping users absent that day. [SentReminder] keeps it to one push per user and due time.
 */
@Service
class TaskReminderService(
    private val taskRepository: TaskRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val sentReminders: SentReminderRepository,
    private val reminders: Reminders,
    private val clock: Clock,
) {
    private data class DueDate(val task: StableTask, val title: String, val dueAt: Long, val occurrenceAt: Long?, val assignees: List<ObjectId>)

    private data class ReminderKey(val taskId: ObjectId, val dueAt: Long, val userId: ObjectId)

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun sendDue() {
        val now = clock.millis()
        sentReminders.deleteByDueAtBefore(now - Reminders.KEEP_SENT_MILLIS)
        val sent = sentReminders.findByDueAtGreaterThan(now).mapTo(mutableSetOf()) { ReminderKey(it.taskId, it.dueAt, it.userId) }
        val run = reminders.run()
        dueDates(now, now + Reminders.WINDOW_MILLIS).forEach { date ->
            val userIds = date.assignees.filter { ReminderKey(date.task.id, date.dueAt, it) !in sent }
            reminders.recipients(run, userIds, date.task.stableId, TYPE).forEach { (user, preferences) -> remind(user, date, preferences, now) }
        }
    }

    private fun dueDates(now: Long, until: Long): List<DueDate> {
        val single = taskRepository.findOpenSingleDue(now, until).map { DueDate(it, it.title, it.dueAt!!, null, it.assigneeUserIds) }
        val moved = occurrenceRepository.findMovedDue(now, until)
        val series = (taskRepository.findSeriesStartingBy(until) + taskRepository.findByIdIn(moved.map { it.taskId }.toSet()))
            .filter { !it.deleted && it.recurrence != null && it.dueAt != null }
            .distinctBy { it.id }
        val changes = occurrenceRepository.findByTaskIdInAndDeletedFalse(series.map { it.id }).groupBy { it.taskId }
        return single + series.flatMap { task ->
            // One broken series must not stop every other reminder.
            try {
                seriesDates(task, changes[task.id].orEmpty().associateBy { it.occurrenceDueAt }, now, until)
            } catch (e: RuntimeException) {
                log.warn("Task {}: dates for reminders failed: {}", task.id, e.message)
                emptyList()
            }
        }
    }

    private fun seriesDates(task: StableTask, changes: Map<Long, TaskOccurrence>, now: Long, until: Long): List<DueDate> {
        val keys = task.recurrence!!.starts(task.dueAt!!).takeWhile { it <= until }.filter { it > now }.toSet() + changes.keys
        return keys.mapNotNull { key ->
            val change = changes[key]
            val dueAt = change?.dueAt ?: key
            if (dueAt <= now || dueAt > until || change?.cancelled == true || change?.doneAt != null) return@mapNotNull null
            val assignees = change?.assigneeUserIds ?: task.rotationAssignee(key)?.let(::listOf) ?: task.assigneeUserIds
            DueDate(task, change?.title ?: task.title, dueAt, key, assignees)
        }
    }

    private fun remind(user: User, date: DueDate, preferences: NotificationPreferences, now: Long) {
        val remindAt = preferences.taskReminderAt(date.dueAt) ?: return
        if (now < remindAt || reminders.isAbsent(user, date.dueAt, preferences.zone)) return
        try {
            sentReminders.insert(SentReminder(stableId = date.task.stableId, taskId = date.task.id, dueAt = date.dueAt, userId = user.id))
        } catch (e: DuplicateKeyException) {
            return
        }
        val data = buildMap {
            put("taskId", date.task.id.toHexString())
            if (date.occurrenceAt != null) put("occurrenceAt", date.occurrenceAt.toString())
        }
        reminders.send(user, TYPE, "Erinnerung: ${date.title}", "Fällig ${formatPushTime(date.dueAt, preferences.zone)}", data)
    }

    companion object {
        const val TYPE = "task_reminder"
    }
}
