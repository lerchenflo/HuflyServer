package com.lerchenflo.hufly.server.event.model

import com.lerchenflo.hufly.server.core.recurrence.RecurrenceResponse
import com.lerchenflo.hufly.server.core.recurrence.toRecurrenceResponse

/** Timestamps are epoch milliseconds. */
data class EventResponse(
    val id: String,
    val stableId: String,
    val creatorUserId: String,
    val title: String,
    val description: String,
    val startAt: Long,
    val endAt: Long,
    val horseIds: List<String>,
    val recurrence: RecurrenceResponse?,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
    val splitFromEventId: String?,
    val splitFromOccurrenceStartAt: Long?,
    val eventTypeId: String?,
    val maxParticipants: Int?,
    val leaveLockHours: Int?,
    val answerHours: Int?,
    val participantOrder: List<String>,
    val participantsVisible: Boolean,
)

data class EventInvitationResponse(
    val id: String,
    val stableId: String,
    val eventId: String,
    val userId: String,
    val status: InvitationStatus,
    val invitedAt: Long,
    val respondedAt: Long?,
    val occurrenceStartAt: Long?,
    val updatedAt: Long,
    val updatedBy: String,
    val version: Long,
)

fun Event.toEventResponse() = EventResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    creatorUserId = creatorUserId.toHexString(),
    title = title,
    description = description,
    startAt = startAt,
    endAt = endAt,
    horseIds = horseIds.map { it.toHexString() },
    recurrence = recurrence?.toRecurrenceResponse(),
    createdAt = createdAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    version = version,
    splitFromEventId = splitFromEventId?.toHexString(),
    splitFromOccurrenceStartAt = splitFromOccurrenceStartAt,
    eventTypeId = eventTypeId?.toHexString(),
    maxParticipants = maxParticipants,
    leaveLockHours = leaveLockHours,
    answerHours = answerHours,
    participantOrder = participantOrder.map { it.toHexString() },
    participantsVisible = participantsVisible,
)

fun EventInvitation.toEventInvitationResponse() = EventInvitationResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    eventId = eventId.toHexString(),
    userId = userId.toHexString(),
    status = status,
    invitedAt = invitedAt,
    respondedAt = respondedAt,
    occurrenceStartAt = occurrenceStartAt,
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    version = version,
)
