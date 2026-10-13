package com.lerchenflo.hufly.server.note.model

import com.lerchenflo.hufly.server.core.sync.VersionSynced
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** A stable notice ("Aushang"). Written with NOTE_WRITE, read by every member. Synced by [version]. */
@Document("notes")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class StableNote(
    @Id override val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val title: String,
    val body: String,
    val pinned: Boolean,
    /** Inclusive; only shown by the client, nothing expires on the server. */
    val visibleUntil: Long?,
    val createdByUserId: ObjectId,
    val createdAt: Long,
    /** Only the author and the admin get the full list, see [toNoteResponse]. */
    val readByUserIds: List<ObjectId> = emptyList(),
    val updatedAt: Long,
    val updatedBy: ObjectId,
    override val deleted: Boolean = false,
    override val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
) : VersionSynced
