package com.lerchenflo.hufly.server.note.model

import org.bson.types.ObjectId

/** Times are epoch milliseconds, [visibleUntil] is epoch days. */
data class NoteResponse(
    val id: String,
    val stableId: String,
    val title: String,
    val body: String,
    val pinned: Boolean,
    val visibleUntil: Long?,
    val createdByUserId: String,
    val createdAt: Long,
    val readByUserIds: List<String>,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

/** Readers other than [viewerId] stay hidden unless [seesAllReaders] (author and admin). */
fun StableNote.toNoteResponse(viewerId: ObjectId, seesAllReaders: Boolean) = NoteResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    title = title,
    body = body,
    pinned = pinned,
    visibleUntil = visibleUntil,
    createdByUserId = createdByUserId.toHexString(),
    createdAt = createdAt,
    readByUserIds = readByUserIds.filter { seesAllReaders || it == viewerId }.map { it.toHexString() },
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    version = version,
)
