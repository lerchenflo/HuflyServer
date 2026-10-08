package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.recurrence.Weekday
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.plusSeconds
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Limit
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoOccurrenceRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var taskRepository: TaskRepository
    @Autowired lateinit var occurrenceRepository: EventOccurrenceRepository
    @Autowired lateinit var answerRepository: EventOccurrenceAnswerRepository
    @Autowired lateinit var taskOccurrenceRepository: TaskOccurrenceRepository

    private val user = ObjectId.get()
    private val key = millis("2026-10-20T16:00:00Z")

    private fun occurrence(version: Long, eventId: ObjectId = ObjectId.get(), stableId: ObjectId = STABLE_ID, at: Long = key) = EventOccurrence(
        stableId = stableId, eventId = eventId, occurrenceStartAt = at, cancelled = false, title = null, description = null,
        startAt = null, endAt = null, horseIds = null, updatedAt = 0L, updatedBy = user, version = version,
    )

    private fun answer(version: Long, invitationId: ObjectId = ObjectId.get(), eventId: ObjectId = ObjectId.get()) = EventOccurrenceAnswer(
        stableId = STABLE_ID, eventId = eventId, invitationId = invitationId, userId = user, occurrenceStartAt = key,
        status = InvitationStatus.ACCEPTED, respondedAt = 0L, updatedAt = 0L, updatedBy = user, version = version,
    )

    private fun taskOccurrence(version: Long, taskId: ObjectId = ObjectId.get()) = TaskOccurrence(
        stableId = STABLE_ID, taskId = taskId, occurrenceDueAt = key, updatedAt = 0L, updatedBy = user, version = version,
    )

    @Test
    fun `event occurrences page by version and are unique per date`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { occurrenceRepository.save(occurrence(it)) }
        occurrenceRepository.save(occurrence(3, stableId = OTHER_STABLE_ID))
        val eventId = ObjectId.get()
        val saved = occurrenceRepository.save(occurrence(6, eventId))
        occurrenceRepository.save(occurrence(7, eventId, at = key.plusSeconds(60)).copy(deleted = true))

        assertEquals(listOf(2L, 3L), occurrenceRepository.findVersionPage(STABLE_ID, 1, 4, Limit.of(2)).map { it.version })
        assertEquals(saved.id, occurrenceRepository.findByEventIdAndOccurrenceStartAt(eventId, key)?.id)
        assertEquals(listOf(saved.id), occurrenceRepository.findByEventIdAndDeletedFalse(eventId).map { it.id })
        assertFailsWith<DuplicateKeyException> { occurrenceRepository.save(occurrence(8, eventId)) }
    }

    @Test
    fun `answers page by version and are unique per invitation and date`() {
        listOf(3L, 1L, 2L).forEach { answerRepository.save(answer(it)) }
        val invitationId = ObjectId.get()
        val eventId = ObjectId.get()
        val saved = answerRepository.save(answer(4, invitationId, eventId))

        assertEquals(listOf(2L, 3L), answerRepository.findVersionPage(STABLE_ID, 1, 3, Limit.of(5)).map { it.version })
        assertEquals(saved.id, answerRepository.findByInvitationIdAndOccurrenceStartAt(invitationId, key)?.id)
        assertEquals(listOf(saved.id), answerRepository.findByEventIdAndDeletedFalse(eventId).map { it.id })
        assertEquals(listOf(saved.id), answerRepository.findByInvitationIdAndDeletedFalse(invitationId).map { it.id })
        assertFailsWith<DuplicateKeyException> { answerRepository.save(answer(5, invitationId, eventId)) }
    }

    @Test
    fun `task occurrences page by version and are unique per date`() {
        listOf(3L, 1L, 2L).forEach { taskOccurrenceRepository.save(taskOccurrence(it)) }
        val taskId = ObjectId.get()
        val saved = taskOccurrenceRepository.save(taskOccurrence(4, taskId))

        assertEquals(listOf(2L, 3L), taskOccurrenceRepository.findVersionPage(STABLE_ID, 1, 3, Limit.of(5)).map { it.version })
        assertEquals(saved.id, taskOccurrenceRepository.findByTaskIdAndOccurrenceDueAt(taskId, key)?.id)
        assertEquals(listOf(saved.id), taskOccurrenceRepository.findByTaskIdAndDeletedFalse(taskId).map { it.id })
        assertFailsWith<DuplicateKeyException> { taskOccurrenceRepository.save(taskOccurrence(5, taskId)) }
    }

    @Test
    fun `dates are found by their own assignees`() {
        val standIn = ObjectId.get()
        val covered = taskOccurrenceRepository.save(taskOccurrence(1).copy(assigneeUserIds = listOf(ObjectId.get(), standIn)))
        taskOccurrenceRepository.save(taskOccurrence(2).copy(assigneeUserIds = listOf(standIn), deleted = true))
        taskOccurrenceRepository.save(taskOccurrence(3))

        assertEquals(listOf(covered.id), taskOccurrenceRepository.findCoveredBy(standIn).map { it.id })
    }

    @Test
    fun `series rules survive a round trip`() {
        val recurrence = Recurrence(RecurrenceFrequency.WEEKLY, 2, listOf(Weekday.TUESDAY, Weekday.THURSDAY), epochDay("2026-12-31"), null, "Europe/Vienna")
        val event = eventRepository.save(
            Event(
                stableId = STABLE_ID, creatorUserId = user, title = "Serie", description = "", startAt = key, endAt = key,
                horseIds = emptyList(), recurrence = recurrence, createdAt = 0L, updatedAt = 0L, updatedBy = user,
            )
        )
        val task = taskRepository.save(
            StableTask(
                stableId = STABLE_ID, title = "Misten", comment = "", dueAt = key, assigneeUserIds = listOf(user), horseIds = emptyList(),
                createdByUserId = user, doneByUserId = null, doneAt = null, updatedAt = 0L, updatedBy = user,
                recurrence = recurrence.copy(frequency = RecurrenceFrequency.DAILY, weekdays = emptyList(), until = null, count = 5),
            )
        )

        assertEquals(recurrence, eventRepository.findById(event.id)?.recurrence)
        assertEquals(task.recurrence, taskRepository.findById(task.id)?.recurrence)
        assertEquals(listOf(task.id), taskRepository.findByIdIn(listOf(task.id, ObjectId.get())).map { it.id })
    }
}
