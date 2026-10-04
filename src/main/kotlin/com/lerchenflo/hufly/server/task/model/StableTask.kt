package com.lerchenflo.hufly.server.task.model

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** A stable chore. Done as soon as any assignee ticks it (TSK-4). Synced by [version]. */
@Document("tasks")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class StableTask(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val title: String,
    val comment: String,
    val dueAt: Instant,
    val assigneeUserIds: List<ObjectId>,
    /** Optional horses the chore is about (HOR-6). */
    val horseIds: List<ObjectId>,
    /** Null for a single task; else [dueAt] is the first date (TSK-5) and dates are ticked one by one. */
    val recurrence: Recurrence? = null,
    val createdByUserId: ObjectId,
    val doneByUserId: ObjectId?,
    val doneAt: Instant?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
)
