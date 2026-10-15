package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface EventOccurrenceRepository : Repository<EventOccurrence, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(occurrence: EventOccurrence): EventOccurrence
    fun findByEventIdAndOccurrenceStartAt(eventId: ObjectId, occurrenceStartAt: Long): EventOccurrence?
    fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrence>
    fun findByEventIdInAndDeletedFalse(eventIds: Collection<ObjectId>): List<EventOccurrence>

    /** Live dates of every stable moved to a start in (from, to]. */
    @Query("{ 'deleted': false, 'startAt': { '\$gt': ?0, '\$lte': ?1 } }")
    fun findMovedStarting(from: Long, to: Long): List<EventOccurrence>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrence>
}

interface EventOccurrenceAnswerRepository : Repository<EventOccurrenceAnswer, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(answer: EventOccurrenceAnswer): EventOccurrenceAnswer
    fun findByInvitationIdAndOccurrenceStartAt(invitationId: ObjectId, occurrenceStartAt: Long): EventOccurrenceAnswer?
    fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventOccurrenceAnswer>
    fun findByInvitationIdAndDeletedFalse(invitationId: ObjectId): List<EventOccurrenceAnswer>
    fun findByEventIdInAndDeletedFalse(eventIds: Collection<ObjectId>): List<EventOccurrenceAnswer>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventOccurrenceAnswer>
}

interface TaskOccurrenceRepository : Repository<TaskOccurrence, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(occurrence: TaskOccurrence): TaskOccurrence
    fun findByTaskIdAndOccurrenceDueAt(taskId: ObjectId, occurrenceDueAt: Long): TaskOccurrence?
    fun findByTaskIdAndDeletedFalse(taskId: ObjectId): List<TaskOccurrence>
    fun findByTaskIdInAndDeletedFalse(taskIds: Collection<ObjectId>): List<TaskOccurrence>

    /** Live dates of every stable moved to a time in (from, to]. */
    @Query("{ 'deleted': false, 'dueAt': { '\$gt': ?0, '\$lte': ?1 } }")
    fun findMovedDue(from: Long, to: Long): List<TaskOccurrence>

    /** Live dates that name [userId] among their own assignees. */
    @Query("{ 'assigneeUserIds': ?0, 'deleted': false }")
    fun findCoveredBy(userId: ObjectId): List<TaskOccurrence>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<TaskOccurrence>
}
