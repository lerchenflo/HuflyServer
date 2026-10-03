package com.lerchenflo.hufly.server.event.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** Mainly riding lessons (EVT-2). Horses are attached to the event, not to riders (EVT-6). */
@Document("events")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class Event(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val creatorUserId: ObjectId,
    val title: String,
    val description: String,
    val startAt: Instant,
    val endAt: Instant,
    val horseIds: List<ObjectId>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
)

enum class InvitationStatus { PENDING, ACCEPTED, DECLINED }

@Document("eventInvitations")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
data class EventInvitation(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val eventId: ObjectId,
    @Indexed val userId: ObjectId,
    val status: InvitationStatus,
    val invitedAt: Instant,
    val respondedAt: Instant?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)
