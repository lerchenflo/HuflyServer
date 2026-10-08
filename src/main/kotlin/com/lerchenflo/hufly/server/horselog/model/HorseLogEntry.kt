package com.lerchenflo.hufly.server.horselog.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** One activity on a horse, typed by an ACTIVITY tag. Vaccinations are entries with [nextDueAt] (HOR-5). */
@Document("horseLog")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class HorseLogEntry(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val horseId: ObjectId,
    val activityTagId: ObjectId,
    val startAt: Long,
    val endAt: Long?,
    val doneByUserId: ObjectId?,
    val comment: String,
    val nextDueAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
)
