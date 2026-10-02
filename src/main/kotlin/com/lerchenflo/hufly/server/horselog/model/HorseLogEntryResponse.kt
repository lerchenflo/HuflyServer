package com.lerchenflo.hufly.server.horselog.model

import java.time.LocalDate

/** Instants are epoch milliseconds, [nextDueAt] is an ISO date. */
data class HorseLogEntryResponse(
    val id: String,
    val stableId: String,
    val horseId: String,
    val activityTagId: String,
    val startAt: Long,
    val endAt: Long?,
    val doneByUserId: String?,
    val comment: String,
    val nextDueAt: LocalDate?,
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
    startAt = startAt.toEpochMilli(),
    endAt = endAt?.toEpochMilli(),
    doneByUserId = doneByUserId?.toHexString(),
    comment = comment,
    nextDueAt = nextDueAt,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
)
