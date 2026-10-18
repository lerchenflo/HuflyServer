package com.lerchenflo.hufly.server.event.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** Held by the one request that checks and changes a lesson's places, server-internal; see `event/LessonLocks`. */
@Document("lessonLocks")
data class LessonLock(
    @Id val eventId: ObjectId,
    @Indexed val stableId: ObjectId,
    /** The holder's; only the holder lets go. */
    val token: ObjectId,
    /** Epoch millis after which another request may take the lock over (its holder died). */
    val until: Long,
)
