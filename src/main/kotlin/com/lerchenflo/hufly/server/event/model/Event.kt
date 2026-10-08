package com.lerchenflo.hufly.server.event.model

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

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
    val startAt: Long,
    val endAt: Long,
    val horseIds: List<ObjectId>,
    /** Null for a single event; else [startAt]/[endAt] are those of the first date (EVT-10). */
    val recurrence: Recurrence? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
    /** Set when the client split this series off [splitFromEventId] ("Diesen und alle folgenden"), see [EventSplit]. */
    val splitFromEventId: ObjectId? = null,
    val splitFromOccurrenceStartAt: Long? = null,
)

/** Invitees of the old series take their answers from [occurrenceStartAt] on over to the new one. */
data class EventSplit(val eventId: ObjectId, val occurrenceStartAt: Long)

enum class InvitationStatus { PENDING, ACCEPTED, DECLINED }

@Document("eventInvitations")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
data class EventInvitation(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val eventId: ObjectId,
    @Indexed val userId: ObjectId,
    val status: InvitationStatus,
    val invitedAt: Long,
    val respondedAt: Long?,
    /** Null invites to the event or the whole series; else to that one date of the series. */
    val occurrenceStartAt: Long? = null,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)
