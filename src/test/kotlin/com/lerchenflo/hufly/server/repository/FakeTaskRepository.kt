package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.task.model.StableTask
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit

class FakeTaskRepository : TaskRepository {
    val tasks = mutableListOf<StableTask>()

    override fun save(task: StableTask): StableTask {
        tasks.requireUniqueClientId(task, { it.id }, { it.stableId }, { it.clientId })
        tasks.removeIf { it.id == task.id }
        tasks += task
        return task
    }

    override fun findById(id: ObjectId): StableTask? = tasks.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): StableTask? =
        tasks.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<StableTask> =
        tasks.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
