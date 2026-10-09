package com.lerchenflo.hufly.server.core.notification

import org.bson.types.ObjectId

/**
 * Something members should hear about, published through Spring's ApplicationEventPublisher and turned into pushes by
 * `notification/NotificationService`. Unlike SchneaggchatV3server, pushes go out whether or not the app holds a socket.
 * Carries ids, not documents, so a listener reloads current data and applies the reader's permissions.
 */
sealed interface NotificationEvent {
    val stableId: ObjectId
    /** The requester; not notified about their own action. */
    val actorUserId: ObjectId
}

data class NotePosted(override val stableId: ObjectId, override val actorUserId: ObjectId, val noteId: ObjectId) : NotificationEvent

/** [userIds] were just invited, to the whole event or only to the date [occurrenceStartAt] of a series. */
data class EventInvited(
    override val stableId: ObjectId,
    override val actorUserId: ObjectId,
    val eventId: ObjectId,
    val userIds: List<ObjectId>,
    val occurrenceStartAt: Long?,
) : NotificationEvent

/** The invitee [actorUserId] changed their answer, for one date of a series when [occurrenceStartAt] is set. */
data class InvitationAnswered(
    override val stableId: ObjectId,
    override val actorUserId: ObjectId,
    val eventId: ObjectId,
    val accepted: Boolean,
    val occurrenceStartAt: Long?,
) : NotificationEvent

/** [userIds] newly have the task, or only its date [occurrenceDueAt] as stand-ins. */
data class TaskAssigned(
    override val stableId: ObjectId,
    override val actorUserId: ObjectId,
    val taskId: ObjectId,
    val userIds: List<ObjectId>,
    val occurrenceDueAt: Long?,
) : NotificationEvent

/** The account [actorUserId] (no member yet, so an account id) asks to join; goes to the stable's admin. */
data class JoinRequested(override val stableId: ObjectId, override val actorUserId: ObjectId, val joinRequestId: ObjectId) : NotificationEvent

/** The admin [actorUserId] accepted a join request; [memberUserId] is the new membership. */
data class JoinAccepted(override val stableId: ObjectId, override val actorUserId: ObjectId, val memberUserId: ObjectId) : NotificationEvent
