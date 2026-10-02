package com.lerchenflo.hufly.server.paddock.model

/** Timestamps are epoch milliseconds. */
data class PaddockResponse(val id: String, val stableId: String, val name: String, val description: String, val updatedAt: Long, val updatedBy: String)

data class HorseGroupResponse(val id: String, val stableId: String, val name: String, val horseIds: List<String>, val updatedAt: Long, val updatedBy: String)

data class HorseConflictResponse(
    val id: String,
    val stableId: String,
    val firstHorseId: String,
    val secondHorseId: String,
    val reason: String,
    val updatedAt: Long,
    val updatedBy: String,
)

data class PaddockAssignmentResponse(
    val id: String,
    val stableId: String,
    val paddockId: String,
    val groupIds: List<String>,
    val horseIds: List<String>,
    val startAt: Long,
    val endAt: Long?,
    val comment: String,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun Paddock.toPaddockResponse() =
    PaddockResponse(id.toHexString(), stableId.toHexString(), name, description, updatedAt.toEpochMilli(), updatedBy.toHexString())

fun HorseGroup.toHorseGroupResponse() = HorseGroupResponse(
    id.toHexString(), stableId.toHexString(), name, horseIds.map { it.toHexString() }, updatedAt.toEpochMilli(), updatedBy.toHexString(),
)

fun HorseConflict.toHorseConflictResponse() = HorseConflictResponse(
    id.toHexString(), stableId.toHexString(), firstHorseId.toHexString(), secondHorseId.toHexString(), reason,
    updatedAt.toEpochMilli(), updatedBy.toHexString(),
)

fun PaddockAssignment.toPaddockAssignmentResponse() = PaddockAssignmentResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    paddockId = paddockId.toHexString(),
    groupIds = groupIds.map { it.toHexString() },
    horseIds = horseIds.map { it.toHexString() },
    startAt = startAt.toEpochMilli(),
    endAt = endAt?.toEpochMilli(),
    comment = comment,
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
)
