package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.LessonOffer
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface LessonOfferRepository : Repository<LessonOffer, ObjectId> {
    fun save(offer: LessonOffer): LessonOffer
    fun findAll(): List<LessonOffer>
    fun findByInvitationIdAndOccurrenceStartAt(invitationId: ObjectId, occurrenceStartAt: Long?): LessonOffer?
    fun delete(offer: LessonOffer)
    fun deleteByStableId(stableId: ObjectId): Long
}
