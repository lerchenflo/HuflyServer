package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.task.model.StableTask
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface TaskRepository : Repository<StableTask, ObjectId> {
    fun save(task: StableTask): StableTask
    fun findById(id: ObjectId): StableTask?

    /** A derived `VersionGreaterThanAndVersionLessThanEqual` query puts `version` twice into one document, which Mongo rejects. */
    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<StableTask>
}
