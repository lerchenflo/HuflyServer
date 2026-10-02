package com.lerchenflo.hufly.server.task.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** A stable chore. Done as soon as any assignee ticks it (TSK-4). Synced by [version]. */
@Document("tasks")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
data class StableTask(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val title: String,
    val comment: String,
    val dueAt: Instant,
    val assigneeUserIds: List<ObjectId>,
    val createdByUserId: ObjectId,
    val doneByUserId: ObjectId?,
    val doneAt: Instant?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)
