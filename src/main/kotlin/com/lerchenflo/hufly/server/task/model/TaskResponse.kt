package com.lerchenflo.hufly.server.task.model

/** Timestamps are epoch milliseconds. */
data class TaskResponse(
    val id: String,
    val stableId: String,
    val title: String,
    val comment: String,
    val dueAt: Long,
    val assigneeUserIds: List<String>,
    val createdByUserId: String,
    val doneByUserId: String?,
    val doneAt: Long?,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun StableTask.toTaskResponse() = TaskResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    title = title,
    comment = comment,
    dueAt = dueAt.toEpochMilli(),
    assigneeUserIds = assigneeUserIds.map { it.toHexString() },
    createdByUserId = createdByUserId.toHexString(),
    doneByUserId = doneByUserId?.toHexString(),
    doneAt = doneAt?.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
)
