package com.lerchenflo.hufly.server.event.model

import com.lerchenflo.hufly.server.core.sync.VersionSynced
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
    @Id override val id: ObjectId = ObjectId.get(),
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
    override val deleted: Boolean = false,
    override val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
    /** Set when the client split this series off [splitFromEventId] ("Diesen und alle folgenden"), see [EventSplit]. */
    val splitFromEventId: ObjectId? = null,
    val splitFromOccurrenceStartAt: Long? = null,
    /** The Terminart (an EVENT_TYPE tag) the client copied the lesson fields from; editing the tag changes nothing here. */
    val eventTypeId: ObjectId? = null,
    /** Set makes the event a lesson with places and a waiting list, see [com.lerchenflo.hufly.server.event.LessonPlacement]. */
    val maxParticipants: Int? = null,
    /** Accepted participants cannot leave from this many hours before a date's start on; null or 0: never locked. */
    val leaveLockHours: Int? = null,
    /** How long someone moved up from the waiting list has to accept; null: no limit. */
    val answerHours: Int? = null,
    /** Who gets places first; may name users without an invitation, invitees missing from it come last. */
    val participantOrder: List<ObjectId> = emptyList(),
    /** Whether invitees see who else accepted; the client hides it, the server sends every invitation anyway. */
    val participantsVisible: Boolean = true,
) : VersionSynced

/** The lesson fields of an [Event], all empty for a plain event. */
data class LessonFields(
    val eventTypeId: ObjectId? = null,
    val maxParticipants: Int? = null,
    val leaveLockHours: Int? = null,
    val answerHours: Int? = null,
    val participantOrder: List<ObjectId> = emptyList(),
)

/** Invitees of the old series take their answers from [occurrenceStartAt] on over to the new one. */
data class EventSplit(val eventId: ObjectId, val occurrenceStartAt: Long)

enum class InvitationStatus { PENDING, ACCEPTED, DECLINED }

@Document("eventInvitations")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
data class EventInvitation(
    @Id override val id: ObjectId = ObjectId.get(),
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
    override val deleted: Boolean = false,
    override val version: Long = 0,
) : VersionSynced
