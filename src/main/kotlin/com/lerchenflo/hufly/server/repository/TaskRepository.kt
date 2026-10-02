package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.task.model.StableTask
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.repository.Repository

interface TaskRepository : Repository<StableTask, ObjectId> {
    fun save(task: StableTask): StableTask
    fun findById(id: ObjectId): StableTask?
    fun findByStableIdAndVersionGreaterThanAndVersionLessThanEqualOrderByVersionAsc(
        stableId: ObjectId, since: Long, watermark: Long, limit: Limit,
    ): List<StableTask>
}
