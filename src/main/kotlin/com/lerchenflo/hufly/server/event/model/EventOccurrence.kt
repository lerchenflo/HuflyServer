package com.lerchenflo.hufly.server.event.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * A change to one date of a series (EVT-10), keyed by the date's original start. Null fields keep the series' value.
 * Rows of dates the current rule no longer produces stay; clients ignore them.
 */
@Document("eventOccurrences")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "eventId_occurrenceStartAt", def = "{'eventId': 1, 'occurrenceStartAt': 1}", unique = true)
data class EventOccurrence(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val eventId: ObjectId,
    val occurrenceStartAt: Instant,
    val cancelled: Boolean,
    val title: String?,
    val description: String?,
    val startAt: Instant?,
    val endAt: Instant?,
    val horseIds: List<ObjectId>?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** Series invitees not taking part on this date; their answers for it are kept but ignored. */
    val removedUserIds: List<ObjectId>? = null,
)

/** An invitee's answer for one date of a series invitation; wins over the invitation's own status for that date. */
@Document("eventOccurrenceAnswers")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "invitationId_occurrenceStartAt", def = "{'invitationId': 1, 'occurrenceStartAt': 1}", unique = true)
data class EventOccurrenceAnswer(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    @Indexed val eventId: ObjectId,
    val invitationId: ObjectId,
    val userId: ObjectId,
    val occurrenceStartAt: Instant,
    val status: InvitationStatus,
    val respondedAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)

/** Timestamps are epoch milliseconds. */
data class EventOccurrenceResponse(
    val id: String,
    val stableId: String,
    val eventId: String,
    val occurrenceStartAt: Long,
    val cancelled: Boolean,
    val title: String?,
    val description: String?,
    val startAt: Long?,
    val endAt: Long?,
    val horseIds: List<String>?,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
    val removedUserIds: List<String>?,
)

data class EventOccurrenceAnswerResponse(
    val id: String,
    val stableId: String,
    val eventId: String,
    val invitationId: String,
    val userId: String,
    val occurrenceStartAt: Long,
    val status: InvitationStatus,
    val respondedAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun EventOccurrence.toEventOccurrenceResponse() = EventOccurrenceResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    eventId = eventId.toHexString(),
    occurrenceStartAt = occurrenceStartAt.toEpochMilli(),
    cancelled = cancelled,
    title = title,
    description = description,
    startAt = startAt?.toEpochMilli(),
    endAt = endAt?.toEpochMilli(),
    horseIds = horseIds?.map { it.toHexString() },
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
    removedUserIds = removedUserIds?.map { it.toHexString() },
)

fun EventOccurrenceAnswer.toEventOccurrenceAnswerResponse() = EventOccurrenceAnswerResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    eventId = eventId.toHexString(),
    invitationId = invitationId.toHexString(),
    userId = userId.toHexString(),
    occurrenceStartAt = occurrenceStartAt.toEpochMilli(),
    status = status,
    respondedAt = respondedAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
    version = version,
)
