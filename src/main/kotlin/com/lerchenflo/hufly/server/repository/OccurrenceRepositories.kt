package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant

interface EventOccurrenceRepository : Repository<EventOccurrence, ObjectId> {
    fun save(occurrence: EventOccurrence): EventOccurrence
    fun findByEventIdAndOccurrenceStartAt(eventId: ObjectId, occurrenceStartAt: Instant): EventOccurrence?
    fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrence>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrence>
}

interface EventOccurrenceAnswerRepository : Repository<EventOccurrenceAnswer, ObjectId> {
    fun save(answer: EventOccurrenceAnswer): EventOccurrenceAnswer
    fun findByInvitationIdAndOccurrenceStartAt(invitationId: ObjectId, occurrenceStartAt: Instant): EventOccurrenceAnswer?
    fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrenceAnswer>
    fun findByInvitationIdAndDeletedFalse(invitationId: ObjectId): List<EventOccurrenceAnswer>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrenceAnswer>
}

interface TaskOccurrenceRepository : Repository<TaskOccurrence, ObjectId> {
    fun save(occurrence: TaskOccurrence): TaskOccurrence
    fun findByTaskIdAndOccurrenceDueAt(taskId: ObjectId, occurrenceDueAt: Instant): TaskOccurrence?
    fun findByTaskIdAndDeletedFalse(taskId: ObjectId): List<TaskOccurrence>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<TaskOccurrence>
}
