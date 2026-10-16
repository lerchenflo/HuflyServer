package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.LessonOffer
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

class FakeLessonOfferRepository : LessonOfferRepository {
    val offers = mutableListOf<LessonOffer>()

    override fun save(offer: LessonOffer): LessonOffer {
        if (offers.any { it.id != offer.id && it.invitationId == offer.invitationId && it.occurrenceStartAt == offer.occurrenceStartAt }) {
            throw DuplicateKeyException("invitationId_occurrenceStartAt")
        }
        val index = offers.indexOfFirst { it.id == offer.id }
        if (index >= 0) offers[index] = offer else offers += offer
        return offer
    }

    override fun findAll(): List<LessonOffer> = offers.toList()

    override fun findByInvitationIdAndOccurrenceStartAt(invitationId: ObjectId, occurrenceStartAt: Long?): LessonOffer? =
        offers.firstOrNull { it.invitationId == invitationId && it.occurrenceStartAt == occurrenceStartAt }

    override fun delete(offer: LessonOffer) {
        offers.removeIf { it.id == offer.id }
    }

    override fun deleteByStableId(stableId: ObjectId): Long =
        offers.count { it.stableId == stableId }.toLong().also { offers.removeIf { it.stableId == stableId } }
}
