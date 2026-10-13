package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.task.model.StableTask
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface TaskRepository : Repository<StableTask, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(task: StableTask): StableTask
    fun findById(id: ObjectId): StableTask?
    fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): StableTask?
    fun findByIdIn(ids: Collection<ObjectId>): List<StableTask>
    fun findByTurnoutAssignmentIdAndDeletedFalse(turnoutAssignmentId: ObjectId): List<StableTask>

    /** Open single tasks of every stable due in (from, to]. */
    @Query("{ 'deleted': false, 'recurrence': null, 'doneAt': null, 'dueAt': { '\$gt': ?0, '\$lte': ?1 } }")
    fun findOpenSingleDue(from: Long, to: Long): List<StableTask>

    /** Live series of every stable whose first date is at or before [to]. */
    @Query("{ 'deleted': false, 'recurrence': { '\$ne': null }, 'dueAt': { '\$lte': ?0 } }")
    fun findSeriesStartingBy(to: Long): List<StableTask>

    /** A derived `VersionGreaterThanAndVersionLessThanEqual` query puts `version` twice into one document, which Mongo rejects. */
    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<StableTask>
}
