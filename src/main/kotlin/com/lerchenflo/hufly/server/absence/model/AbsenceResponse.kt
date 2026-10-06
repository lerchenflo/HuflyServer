package com.lerchenflo.hufly.server.absence.model

import java.time.LocalDate

/** Instants are epoch milliseconds, [from] and [until] ISO dates. */
data class AbsenceResponse(
    val id: String,
    val stableId: String,
    val userId: String,
    val from: LocalDate,
    val until: LocalDate,
    val note: String,
    val createdByUserId: String,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
    val deleted: Boolean,
)

fun Absence.toAbsenceResponse() = AbsenceResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    userId = userId.toHexString(),
    from = from,
    until = until,
    note = note,
    createdByUserId = createdByUserId.toHexString(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
    deleted = deleted,
)
