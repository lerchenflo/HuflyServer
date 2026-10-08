package com.lerchenflo.hufly.server.horselog.model


/** Times are epoch milliseconds, [nextDueAt] is epoch days. */
data class HorseLogEntryResponse(
    val id: String,
    val stableId: String,
    val horseId: String,
    val activityTagId: String,
    val startAt: Long,
    val endAt: Long?,
    val doneByUserId: String?,
    val comment: String,
    val nextDueAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun HorseLogEntry.toHorseLogEntryResponse() = HorseLogEntryResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    horseId = horseId.toHexString(),
    activityTagId = activityTagId.toHexString(),
    startAt = startAt,
    endAt = endAt,
    doneByUserId = doneByUserId?.toHexString(),
    comment = comment,
    nextDueAt = nextDueAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    version = version,
)
