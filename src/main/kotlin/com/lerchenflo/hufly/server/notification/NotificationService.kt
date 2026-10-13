package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.notification.EventInvited
import com.lerchenflo.hufly.server.core.notification.InvitationAnswered
import com.lerchenflo.hufly.server.core.notification.JoinAccepted
import com.lerchenflo.hufly.server.core.notification.JoinRequested
import com.lerchenflo.hufly.server.core.notification.NotePosted
import com.lerchenflo.hufly.server.core.notification.NotificationEvent
import com.lerchenflo.hufly.server.core.notification.TaskAssigned
import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.SentReminder
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.DigestItemRepository
import com.lerchenflo.hufly.server.repository.SentReminderRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.NoteRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.user.model.User
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.dao.DuplicateKeyException
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
    private val accountRepository: AccountRepository,
    private val stableRepository: StableRepository,
    private val settingsRepository: UserSettingsRepository,
    private val noteRepository: NoteRepository,
    private val eventRepository: EventRepository,
    private val taskRepository: TaskRepository,
    private val pushService: PushService,
    private val digestRepository: DigestItemRepository,
    private val sentReminders: SentReminderRepository,
    private val pushTitles: PushTitles,
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
                is JoinRequested -> joinRequested(event)
                is JoinAccepted -> joinAccepted(event)
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
        val namedDueAt = event.occurrenceDueAt ?: task.dueAt
        val onPushed = { user: User, preferences: NotificationPreferences ->
            if (namedDueAt != null) markReminded(task, namedDueAt, user, preferences)
        }
        deliver(members(event, event.userIds), onPushed) { zone ->
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

    private fun joinRequested(event: JoinRequested) {
        val stable = stableRepository.findById(event.stableId)?.takeUnless { it.deleted } ?: return
        val admin = userRepository.findById(stable.adminUserId)?.takeIf { it.stableId == stable.id && !it.deleted } ?: return
        val name = accountRepository.findById(event.actorUserId)?.takeUnless { it.deleted }?.displayName ?: return
        deliver(listOf(admin)) {
            PushMessage(
                "Beitrittsanfrage",
                "$name möchte ${stable.name} beitreten",
                mapOf("type" to "join_request", "joinRequestId" to event.joinRequestId.toHexString()),
            )
        }
    }

    private fun joinAccepted(event: JoinAccepted) {
        val stable = stableRepository.findById(event.stableId)?.takeUnless { it.deleted } ?: return
        val member = userRepository.findById(event.memberUserId)?.takeIf { it.stableId == stable.id && !it.deleted } ?: return
        deliver(listOf(member)) {
            PushMessage(
                "Willkommen im Stall",
                "Deine Anfrage für ${stable.name} wurde angenommen",
                mapOf("type" to "join_accepted", "stableId" to stable.id.toHexString()),
            )
        }
    }

    /** Live users of the event's stable among [userIds], without the actor. */
    private fun members(event: NotificationEvent, userIds: List<ObjectId>): List<User> = userIds.distinct()
        .filter { it != event.actorUserId }
        .mapNotNull { id -> userRepository.findById(id)?.takeIf { it.stableId == event.stableId && !it.deleted } }

    /** A push that named a due time already inside the user's reminder window replaces that reminder. */
    private fun markReminded(task: StableTask, dueAt: Long, user: User, preferences: NotificationPreferences) {
        val remindAt = preferences.taskReminderAt(dueAt) ?: return
        val now = clock.millis()
        if (now < remindAt || now >= dueAt) return
        try {
            sentReminders.insert(SentReminder(stableId = task.stableId, taskId = task.id, dueAt = dueAt, userId = user.id))
        } catch (e: DuplicateKeyException) {
            // Reminded already.
        }
    }

    private fun deliver(
        recipients: List<User>,
        onPushed: (User, NotificationPreferences) -> Unit = { _, _ -> },
        message: (TimeZone) -> PushMessage,
    ) {
        recipients.forEach { user ->
            val preferences = NotificationPreferences.of(settingsRepository.findById(user.accountId)?.values)
            // The stable lets an app with several stables open the right one on a tap.
            val push = message(preferences.zone).let { message ->
                val type = message.data["type"]
                message.copy(
                    title = if (type == "join_accepted") message.title else pushTitles.of(user, message.title),
                    data = message.data + ("stableId" to user.stableId.toHexString()),
                )
            }
            val type = push.data["type"]
            if (!preferences.enabled || preferences.mutes(type)) {
                log.info("Notification {} for account {}: muted", type, user.accountId)
                return@forEach
            }
            if (preferences.digest) {
                log.info("Notification {} for account {}: held for the digest", type, user.accountId)
                digestRepository.save(DigestItem(userId = user.accountId, title = push.title, body = push.body, createdAt = clock.millis()))
            } else {
                pushService.send(user.accountId, push)
                onPushed(user, preferences)
            }
        }
    }

    private fun data(type: String, id: Pair<String, ObjectId>, occurrenceAt: Long?) = buildMap {
        put("type", type)
        put(id.first, id.second.toHexString())
        if (occurrenceAt != null) put("occurrenceAt", occurrenceAt.toString())
    }

    private fun format(at: Long, zone: TimeZone) = formatPushTime(at, zone)
}

private val WEEKDAYS = listOf("Mo.", "Di.", "Mi.", "Do.", "Fr.", "Sa.", "So.")

/** Like "Fr. 09.10. 17:00". */
internal fun formatPushTime(at: Long, zone: TimeZone): String {
    val local = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone)
    fun Int.twoDigits() = toString().padStart(2, '0')
    return "${WEEKDAYS[local.dayOfWeek.isoDayNumber - 1]} ${local.day.twoDigits()}.${local.month.number.twoDigits()}. " +
        "${local.hour.twoDigits()}:${local.minute.twoDigits()}"
}
