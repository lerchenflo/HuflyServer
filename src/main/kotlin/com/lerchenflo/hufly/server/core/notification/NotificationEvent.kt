package com.lerchenflo.hufly.server.core.notification

import org.bson.types.ObjectId

/**
 * Something members should hear about, published through Spring's ApplicationEventPublisher. Nothing listens yet;
 * push notifications will (TODO.md): unlike SchneaggchatV3server they go out whether or not the app holds a socket.
 * Carries ids, not documents, so a listener reloads current data and applies the reader's permissions.
 */
sealed interface NotificationEvent {
    val stableId: ObjectId
    /** The requester; not notified about their own action. */
    val actorUserId: ObjectId
}

data class NotePosted(override val stableId: ObjectId, override val actorUserId: ObjectId, val noteId: ObjectId) : NotificationEvent
