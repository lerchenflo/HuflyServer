package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Limit

class FakeEventOccurrenceRepository : EventOccurrenceRepository {
    val occurrences = mutableListOf<EventOccurrence>()

    override fun deleteByStableId(stableId: ObjectId): Long = occurrences.count { it.stableId == stableId }.toLong().also { occurrences.removeIf { it.stableId == stableId } }

    override fun save(occurrence: EventOccurrence): EventOccurrence {
        if (occurrences.any { it.id != occurrence.id && it.eventId == occurrence.eventId && it.occurrenceStartAt == occurrence.occurrenceStartAt }) {
            throw DuplicateKeyException("eventId_occurrenceStartAt")
        }
        occurrences.removeIf { it.id == occurrence.id }
        occurrences += occurrence
        return occurrence
    }

    override fun findByEventIdAndOccurrenceStartAt(eventId: ObjectId, occurrenceStartAt: Long): EventOccurrence? =
        occurrences.firstOrNull { it.eventId == eventId && it.occurrenceStartAt == occurrenceStartAt }

    override fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrence> =
        occurrences.filter { it.eventId == eventId && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrence> =
        occurrences.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}

class FakeEventOccurrenceAnswerRepository : EventOccurrenceAnswerRepository {
    val answers = mutableListOf<EventOccurrenceAnswer>()

    override fun deleteByStableId(stableId: ObjectId): Long = answers.count { it.stableId == stableId }.toLong().also { answers.removeIf { it.stableId == stableId } }

    override fun save(answer: EventOccurrenceAnswer): EventOccurrenceAnswer {
        if (answers.any { it.id != answer.id && it.invitationId == answer.invitationId && it.occurrenceStartAt == answer.occurrenceStartAt }) {
            throw DuplicateKeyException("invitationId_occurrenceStartAt")
        }
        answers.removeIf { it.id == answer.id }
        answers += answer
        return answer
    }

    override fun findByInvitationIdAndOccurrenceStartAt(invitationId: ObjectId, occurrenceStartAt: Long): EventOccurrenceAnswer? =
        answers.firstOrNull { it.invitationId == invitationId && it.occurrenceStartAt == occurrenceStartAt }

    override fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrenceAnswer> =
        answers.filter { it.eventId == eventId && !it.deleted }

    override fun findByInvitationIdAndDeletedFalse(invitationId: ObjectId): List<EventOccurrenceAnswer> =
        answers.filter { it.invitationId == invitationId && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrenceAnswer> =
        answers.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}

class FakeTaskOccurrenceRepository : TaskOccurrenceRepository {
    val occurrences = mutableListOf<TaskOccurrence>()

    override fun deleteByStableId(stableId: ObjectId): Long = occurrences.count { it.stableId == stableId }.toLong().also { occurrences.removeIf { it.stableId == stableId } }

    override fun save(occurrence: TaskOccurrence): TaskOccurrence {
        if (occurrences.any { it.id != occurrence.id && it.taskId == occurrence.taskId && it.occurrenceDueAt == occurrence.occurrenceDueAt }) {
            throw DuplicateKeyException("taskId_occurrenceDueAt")
        }
        occurrences.removeIf { it.id == occurrence.id }
        occurrences += occurrence
        return occurrence
    }

    override fun findByTaskIdAndOccurrenceDueAt(taskId: ObjectId, occurrenceDueAt: Long): TaskOccurrence? =
        occurrences.firstOrNull { it.taskId == taskId && it.occurrenceDueAt == occurrenceDueAt }

    override fun findByTaskIdAndDeletedFalse(taskId: ObjectId): List<TaskOccurrence> =
        occurrences.filter { it.taskId == taskId && !it.deleted }

    override fun findCoveredBy(userId: ObjectId): List<TaskOccurrence> =
        occurrences.filter { it.assigneeUserIds?.contains(userId) == true && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<TaskOccurrence> =
        occurrences.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
