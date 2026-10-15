package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.event.EventParticipation
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.SentEventReminder
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.SentEventReminderRepository
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Reminds the invitees who accepted an event or series date `eventReminderMinutes` before it starts, at once also for
 * digest users, skipping users absent that day. [SentEventReminder] keeps it to one push per user and start.
 */
@Service
class EventReminderService(
    private val eventRepository: EventRepository,
    private val invitationRepository: EventInvitationRepository,
    private val occurrenceRepository: EventOccurrenceRepository,
    private val answerRepository: EventOccurrenceAnswerRepository,
    private val sentReminders: SentEventReminderRepository,
    private val reminders: Reminders,
    private val clock: Clock,
) {
    private data class Start(val event: Event, val title: String, val startAt: Long, val occurrenceAt: Long?, val userIds: List<ObjectId>)

    private data class ReminderKey(val eventId: ObjectId, val startAt: Long, val userId: ObjectId)

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun sendDue() {
        val now = clock.millis()
        sentReminders.deleteByStartAtBefore(now - Reminders.KEEP_SENT_MILLIS)
        val sent = sentReminders.findByStartAtGreaterThan(now).mapTo(mutableSetOf()) { ReminderKey(it.eventId, it.startAt, it.userId) }
        val run = reminders.run()
        starts(now, now + Reminders.WINDOW_MILLIS).forEach { start ->
            val userIds = start.userIds.filter { ReminderKey(start.event.id, start.startAt, it) !in sent }
            reminders.recipients(run, userIds, start.event.stableId, TYPE).forEach { (user, preferences) -> remind(user, start, preferences, now) }
        }
    }

    private fun starts(now: Long, until: Long): List<Start> {
        val singles = eventRepository.findSingleStarting(now, until)
        val moved = occurrenceRepository.findMovedStarting(now, until)
        val series = (eventRepository.findSeriesStartingBy(until) + eventRepository.findByIdIn(moved.map { it.eventId }.toSet()))
            .filter { !it.deleted && it.recurrence != null }
            .distinctBy { it.id }
        val invitations = invitationRepository.findByEventIdInAndDeletedFalse((singles + series).map { it.id }).groupBy { it.eventId }
        val changes = occurrenceRepository.findByEventIdInAndDeletedFalse(series.map { it.id }).groupBy { it.eventId }
        val answers = answerRepository.findByEventIdInAndDeletedFalse(series.map { it.id }).groupBy { it.eventId }
        fun participation(event: Event) =
            EventParticipation(event, invitations[event.id].orEmpty(), changes[event.id].orEmpty(), answers[event.id].orEmpty())

        return singles.map { Start(it, it.title, it.startAt, null, participation(it).acceptedOn(it.startAt)) } + series.flatMap { event ->
            // One broken series must not stop every other reminder.
            try {
                seriesStarts(event, participation(event), changes[event.id].orEmpty().associateBy { it.occurrenceStartAt }, now, until)
            } catch (e: RuntimeException) {
                log.warn("Event {}: dates for reminders failed: {}", event.id, e.message)
                emptyList()
            }
        }
    }

    private fun seriesStarts(event: Event, participation: EventParticipation, changes: Map<Long, EventOccurrence>, now: Long, until: Long): List<Start> {
        val recurrence = event.recurrence!!
        val keys = recurrence.starts(event.startAt).takeWhile { it <= until }.filter { it > now }.toSet() +
            changes.keys.filter { recurrence.isOccurrence(event.startAt, it) }
        return keys.mapNotNull { key ->
            val change = changes[key]
            val startAt = change?.startAt ?: key
            if (startAt <= now || startAt > until || change?.cancelled == true) return@mapNotNull null
            Start(event, change?.title ?: event.title, startAt, key, participation.acceptedOn(key))
        }
    }

    private fun remind(user: User, start: Start, preferences: NotificationPreferences, now: Long) {
        val remindAt = preferences.eventReminderAt(start.startAt) ?: return
        if (now < remindAt || reminders.isAbsent(user, start.startAt, preferences.zone)) return
        try {
            sentReminders.insert(SentEventReminder(stableId = start.event.stableId, eventId = start.event.id, startAt = start.startAt, userId = user.id))
        } catch (e: DuplicateKeyException) {
            return
        }
        val data = buildMap {
            put("eventId", start.event.id.toHexString())
            if (start.occurrenceAt != null) put("occurrenceAt", start.occurrenceAt.toString())
        }
        reminders.send(user, TYPE, "Erinnerung: ${start.title}", "Beginn ${formatPushTime(start.startAt, preferences.zone)}", data)
    }

    companion object {
        const val TYPE = "event_reminder"
    }
}
