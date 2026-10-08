package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.notification.EventInvited
import com.lerchenflo.hufly.server.core.notification.InvitationAnswered
import com.lerchenflo.hufly.server.core.notification.NotePosted
import com.lerchenflo.hufly.server.core.notification.NotificationEvent
import com.lerchenflo.hufly.server.core.notification.TaskAssigned
import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.repository.DigestItemRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.NoteRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.User
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import kotlin.time.Instant

/**
 * Turns [NotificationEvent]s into German pushes. Runs in the request after the change is saved, reloads what it
 * names (deleted means nothing to say), never notifies the actor or other stables, and honours each recipient's
 * [NotificationPreferences]: muted, single pushes, or held for the daily digest ([DigestService]).
 */
@Service
class NotificationService(
    private val userRepository: UserRepository,
    private val settingsRepository: UserSettingsRepository,
    private val noteRepository: NoteRepository,
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val pushService: PushService,
    private val digestRepository: DigestItemRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun on(event: NotificationEvent) {
        try {
            when (event) {
                is NotePosted -> notePosted(event)
                is EventInvited -> eventInvited(event)
                is InvitationAnswered -> invitationAnswered(event)
                is TaskAssigned -> taskAssigned(event)
            }
        } catch (e: Exception) {
            // A failing push must never fail the change that caused it.
            log.warn("Notification for {} failed: {}", event, e.message)
        }
    }

    private fun notePosted(event: NotePosted) {
        val note = noteRepository.findById(event.noteId)?.takeIf { it.stableId == event.stableId && !it.deleted } ?: return
        val recipients = userRepository.findByStableIdAndDeletedFalse(event.stableId).filter { it.id != event.actorUserId }
        deliver(recipients) { PushMessage("Neuer Aushang", note.title, mapOf("type" to "note", "noteId" to note.id.toHexString())) }
    }

    private fun eventInvited(event: EventInvited) {
        val calendarEvent = eventRepository.findById(event.eventId)?.takeIf { it.stableId == event.stableId && !it.deleted } ?: return
        val from = userRepository.findById(event.actorUserId)?.displayName ?: return
        val at = event.occurrenceStartAt ?: calendarEvent.startAt
        deliver(members(event, event.userIds)) { zone ->
            PushMessage(
                "Einladung: ${calendarEvent.title}",
                "${format(at, zone)} · von $from",
                data("event_invitation", "eventId" to calendarEvent.id, event.occurrenceStartAt),
            )
        }
    }

    private fun invitationAnswered(event: InvitationAnswered) {
        val calendarEvent = eventRepository.findById(event.eventId)?.takeIf { it.stableId == event.stableId && !it.deleted } ?: return
        val who = userRepository.findById(event.actorUserId)?.displayName ?: return
        val at = event.occurrenceStartAt ?: calendarEvent.startAt
        deliver(members(event, listOf(calendarEvent.creatorUserId))) { zone ->
            PushMessage(
                "$who hat ${if (event.accepted) "zugesagt" else "abgesagt"}",
                "${calendarEvent.title}, ${format(at, zone)}",
                data("invitation_answer", "eventId" to calendarEvent.id, event.occurrenceStartAt),
            )
        }
    }

    private fun taskAssigned(event: TaskAssigned) {
        val task = taskRepository.findById(event.taskId)?.takeIf { it.stableId == event.stableId && !it.deleted } ?: return
        deliver(members(event, event.userIds)) { zone ->
            val dueAt = task.dueAt
            val (title, body) = when {
                event.occurrenceDueAt != null -> "Vertretung: ${task.title}" to "Am ${format(event.occurrenceDueAt, zone)}"
                dueAt == null -> "Neue Aufgabe: ${task.title}" to "Ohne Termin"
                task.recurrence != null -> "Neue Aufgabe: ${task.title}" to "Wiederkehrend ab ${format(dueAt, zone)}"
                else -> "Neue Aufgabe: ${task.title}" to "Fällig ${format(dueAt, zone)}"
            }
            PushMessage(title, body, data("task_assigned", "taskId" to task.id, event.occurrenceDueAt))
        }
    }

    /** Live users of the event's stable among [userIds], without the actor. */
    private fun members(event: NotificationEvent, userIds: List<ObjectId>): List<User> = userIds.distinct()
        .filter { it != event.actorUserId }
        .mapNotNull { id -> userRepository.findById(id)?.takeIf { it.stableId == event.stableId && !it.deleted } }

    private fun deliver(recipients: List<User>, message: (TimeZone) -> PushMessage) {
        recipients.forEach { user ->
            val preferences = NotificationPreferences.of(settingsRepository.findById(user.id)?.values)
            if (!preferences.enabled) return@forEach
            val push = message(preferences.zone)
            if (preferences.digest) {
                digestRepository.save(DigestItem(userId = user.id, title = push.title, body = push.body, createdAt = clock.millis()))
            } else {
                pushService.send(user.id, push)
            }
        }
    }

    private fun data(type: String, id: Pair<String, ObjectId>, occurrenceAt: Long?) = buildMap {
        put("type", type)
        put(id.first, id.second.toHexString())
        if (occurrenceAt != null) put("occurrenceAt", occurrenceAt.toString())
    }

    /** Like "Fr. 09.10. 17:00". */
    private fun format(at: Long, zone: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
        fun Int.twoDigits() = toString().padStart(2, '0')
        return "${WEEKDAYS[local.dayOfWeek.isoDayNumber - 1]} ${local.day.twoDigits()}.${local.month.number.twoDigits()}. " +
            "${local.hour.twoDigits()}:${local.minute.twoDigits()}"
    }

    companion object {
        private val WEEKDAYS = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")
    }
}
