package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_HOUR
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.LessonOffer
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.LessonOfferRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Ends offers from a lesson's waiting list: unanswered `answerHours` after the offer (or at the date's start) they are
 * declined and the place goes to the next one waiting. Offers without an answer time end at the start; offers whose
 * user answered, fell back to waiting or lost the invitation, date or event end at once.
 */
@Service
class LessonOfferTimeouts(
    private val offerRepository: LessonOfferRepository,
    private val eventRepository: EventRepository,
    private val lessons: LessonService,
    private val eventService: EventService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun expireOffers() {
        val now = clock.millis()
        offerRepository.findAll().groupBy { it.eventId }.forEach { (eventId, offers) ->
            // One broken event must not stop every other timeout.
            try {
                expire(eventId, offers, now)
            } catch (e: RuntimeException) {
                log.warn("Event {}: lesson offers failed: {}", eventId, e.message)
            }
        }
    }

    private fun expire(eventId: ObjectId, offers: List<LessonOffer>, now: Long) {
        val event = eventRepository.findById(eventId)?.takeIf { !it.deleted && it.maxParticipants != null }
            ?: return offers.forEach(offerRepository::delete)
        offers.forEach { offer ->
            val key = offer.occurrenceStartAt
            val placement = lessons.placement(event)
            val seat = placement.seatsOn(key)?.takeIf { placement.isDate(key) }?.firstOrNull { it.invitation.id == offer.invitationId }
            if (seat == null || seat.status != InvitationStatus.PENDING || seat.isWaiting) return@forEach offerRepository.delete(offer)
            val start = placement.startOf(key)
            val answerBy = event.answerHours?.let { minOf(offer.offeredAt + it * MILLIS_PER_HOUR, start) }
            when {
                answerBy != null && now >= answerBy -> {
                    offerRepository.delete(offer)
                    eventService.declineUnanswered(event, seat.invitation, key)
                }
                now >= start -> offerRepository.delete(offer)
            }
        }
    }
}
