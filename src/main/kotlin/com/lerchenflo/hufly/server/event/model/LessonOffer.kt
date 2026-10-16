package com.lerchenflo.hufly.server.event.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * A place on a lesson date offered to someone who moved up from the waiting list, server-internal (never synced).
 * Ends with an answer, or as declined `answerHours` after [offeredAt], see `event/LessonOfferTimeouts`.
 */
@Document("lessonOffers")
@CompoundIndex(name = "invitationId_occurrenceStartAt", def = "{'invitationId': 1, 'occurrenceStartAt': 1}", unique = true)
data class LessonOffer(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val eventId: ObjectId,
    val invitationId: ObjectId,
    val userId: ObjectId,
    /** The date's key: null for a single event, else the date's original start. */
    val occurrenceStartAt: Long?,
    val offeredAt: Long,
)
