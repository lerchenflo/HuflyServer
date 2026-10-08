package com.lerchenflo.hufly.server.absence.model


/** Times are epoch milliseconds, [from] and [until] epoch days. */
data class AbsenceResponse(
    val id: String,
    val stableId: String,
    val userId: String,
    val from: Long,
    val until: Long,
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
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    version = version,
    deleted = deleted,
)
