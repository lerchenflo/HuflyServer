package com.lerchenflo.hufly.server.task.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * One date of a task series (TSK-5), keyed by its original due time: its change (null fields keep the series' value)
 * and its tick. Each endpoint writes only its own fields.
 */
@Document("taskOccurrences")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "taskId_occurrenceDueAt", def = "{'taskId': 1, 'occurrenceDueAt': 1}", unique = true)
data class TaskOccurrence(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val taskId: ObjectId,
    val occurrenceDueAt: Instant,
    val cancelled: Boolean = false,
    val title: String? = null,
    val comment: String? = null,
    val dueAt: Instant? = null,
    val horseIds: List<ObjectId>? = null,
    val doneByUserId: ObjectId? = null,
    val doneAt: Instant? = null,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)

/** Timestamps are epoch milliseconds. */
data class TaskOccurrenceResponse(
    val id: String,
    val stableId: String,
    val taskId: String,
    val occurrenceDueAt: Long,
    val cancelled: Boolean,
    val title: String?,
    val comment: String?,
    val dueAt: Long?,
    val horseIds: List<String>?,
    val doneByUserId: String?,
    val doneAt: Long?,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun TaskOccurrence.toTaskOccurrenceResponse() = TaskOccurrenceResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    taskId = taskId.toHexString(),
    occurrenceDueAt = occurrenceDueAt.toEpochMilli(),
    cancelled = cancelled,
    title = title,
    comment = comment,
    dueAt = dueAt?.toEpochMilli(),
    horseIds = horseIds?.map { it.toHexString() },
    doneByUserId = doneByUserId?.toHexString(),
    doneAt = doneAt?.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
)
