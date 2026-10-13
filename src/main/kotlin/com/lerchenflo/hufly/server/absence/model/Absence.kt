package com.lerchenflo.hufly.server.absence.model

import com.lerchenflo.hufly.server.core.sync.VersionSynced
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** A member is away from [from] to [until], both days included. Only a hint in the app, nothing is blocked. Synced by [version]. */
@Document("absences")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class Absence(
    @Id override val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val userId: ObjectId,
    val from: Long,
    val until: Long,
    val note: String,
    val createdByUserId: ObjectId,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    override val deleted: Boolean = false,
    override val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
) : VersionSynced
