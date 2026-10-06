package com.lerchenflo.hufly.server.absence.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

/** A member is away from [from] to [until], both days included. Only a hint in the app, nothing is blocked. Synced by [version]. */
@Document("absences")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class Absence(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val userId: ObjectId,
    val from: LocalDate,
    val until: LocalDate,
    val note: String,
    val createdByUserId: ObjectId,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
)
