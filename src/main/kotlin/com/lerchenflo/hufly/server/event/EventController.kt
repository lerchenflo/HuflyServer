package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.epochMillisToInstant
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceRequest
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.event.model.EventInvitationResponse
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswerResponse
import com.lerchenflo.hufly.server.event.model.EventOccurrenceResponse
import com.lerchenflo.hufly.server.event.model.EventResponse
import com.lerchenflo.hufly.server.event.model.EventSplit
import com.lerchenflo.hufly.server.event.model.toEventInvitationResponse
import com.lerchenflo.hufly.server.event.model.toEventOccurrenceAnswerResponse
import com.lerchenflo.hufly.server.event.model.toEventOccurrenceResponse
import com.lerchenflo.hufly.server.event.model.toEventResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

@RestController
class EventController(
    private val accessService: AccessService,
    private val eventService: EventService,
    private val occurrenceService: EventOccurrenceService,
) {

    data class EventRequest(
        @field:NotBlank @field:Size(max = 200) val title: String,
        @field:Size(max = 5000) val description: String = "",
        /** Epoch milliseconds. */
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val startAt: Long,
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val endAt: Long,
        @field:Size(max = 50) val horseIds: List<String>,
        /** Only read on create; afterwards use the invitation endpoints. */
        @field:Size(max = 500) val inviteeUserIds: List<String> = emptyList(),
        /** Only read on create. */
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
        /** Null makes it a single event, also on update. */
        val recurrence: RecurrenceRequest? = null,
        /** Only read on create, both or none: the series this one was split off and its first date taken over. */
        val splitFromEventId: String? = null,
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val splitFromOccurrenceStartAt: Long? = null,
    ) {
        fun split(): EventSplit? {
            if ((splitFromEventId == null) != (splitFromOccurrenceStartAt == null)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "splitFromEventId and splitFromOccurrenceStartAt go together")
            }
            if (splitFromEventId == null || splitFromOccurrenceStartAt == null) return null
            return EventSplit(parseObjectId(splitFromEventId), Instant.ofEpochMilli(splitFromOccurrenceStartAt))
        }
    }

    data class InviteRequest(
        @field:Size(min = 1, max = 500) val userIds: List<String>,
        /** Invites to that one date of a series only. */
        val occurrenceStartAt: Long? = null,
    )

    /** Null fields keep the series' value. */
    data class OccurrenceRequest(
        val cancelled: Boolean,
        val title: String?,
        val description: String?,
        val startAt: Long?,
        val endAt: Long?,
        @field:Size(max = 50) val horseIds: List<String>?,
    )

    data class AnswerRequest(val accepted: Boolean)

    @PostMapping("/events")
    fun createEvent(@Valid @RequestBody request: EventRequest): EventResponse {
        val requester = accessService.requester(requireAuth())
        return eventService.createEvent(
            requester, request.title, request.description, Instant.ofEpochMilli(request.startAt), Instant.ofEpochMilli(request.endAt),
            request.horseIds.map(::parseObjectId), request.inviteeUserIds.map(::parseObjectId), request.clientId,
            request.recurrence?.toRecurrence(Instant.ofEpochMilli(request.startAt)),
            request.split(),
        ).toEventResponse()
    }

    @PutMapping("/events/{eventId}")
    fun updateEvent(@PathVariable eventId: String, @Valid @RequestBody request: EventRequest): EventResponse {
        val requester = accessService.requester(requireAuth())
        return eventService.updateEvent(
            requester, parseObjectId(eventId), request.title, request.description, Instant.ofEpochMilli(request.startAt),
            Instant.ofEpochMilli(request.endAt), request.horseIds.map(::parseObjectId),
            request.recurrence?.toRecurrence(Instant.ofEpochMilli(request.startAt)),
        ).toEventResponse()
    }

    @DeleteMapping("/events/{eventId}")
    fun deleteEvent(@PathVariable eventId: String) {
        val requester = accessService.requester(requireAuth())
        eventService.deleteEvent(requester, parseObjectId(eventId))
    }

    @PostMapping("/events/{eventId}/invitations")
    fun invite(@PathVariable eventId: String, @Valid @RequestBody request: InviteRequest): List<EventInvitationResponse> {
        val requester = accessService.requester(requireAuth())
        return eventService.invite(
            requester, parseObjectId(eventId), request.userIds.map(::parseObjectId), request.occurrenceStartAt?.let(::epochMillisToInstant),
        ).map { it.toEventInvitationResponse() }
    }

    @PutMapping("/events/{eventId}/occurrences/{occurrenceStartAt}")
    fun putOccurrence(
        @PathVariable eventId: String,
        @PathVariable occurrenceStartAt: Long,
        @Valid @RequestBody request: OccurrenceRequest,
    ): EventOccurrenceResponse {
        val requester = accessService.requester(requireAuth())
        val change = EventOccurrenceChange(
            cancelled = request.cancelled,
            title = request.title,
            description = request.description,
            startAt = request.startAt?.let(::epochMillisToInstant),
            endAt = request.endAt?.let(::epochMillisToInstant),
            horseIds = request.horseIds?.map(::parseObjectId),
        )
        return occurrenceService.putOccurrence(requester, parseObjectId(eventId), epochMillisToInstant(occurrenceStartAt), change)
            .toEventOccurrenceResponse()
    }

    @PutMapping("/eventinvitations/{invitationId}/occurrences/{occurrenceStartAt}/answer")
    fun answerOccurrence(
        @PathVariable invitationId: String,
        @PathVariable occurrenceStartAt: Long,
        @RequestBody request: AnswerRequest,
    ): EventOccurrenceAnswerResponse {
        val requester = accessService.requester(requireAuth())
        return occurrenceService.answer(requester, parseObjectId(invitationId), epochMillisToInstant(occurrenceStartAt), request.accepted)
            .toEventOccurrenceAnswerResponse()
    }

    @DeleteMapping("/eventinvitations/{invitationId}")
    fun removeInvitation(@PathVariable invitationId: String) {
        val requester = accessService.requester(requireAuth())
        eventService.removeInvitation(requester, parseObjectId(invitationId))
    }

    @PostMapping("/eventinvitations/{invitationId}/answer")
    fun answer(@PathVariable invitationId: String, @RequestBody request: AnswerRequest): EventInvitationResponse {
        val requester = accessService.requester(requireAuth())
        return eventService.respond(requester, parseObjectId(invitationId), request.accepted).toEventInvitationResponse()
    }

    @GetMapping("/events/sync")
    fun syncEvents(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<EventResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return eventService.syncEvents(requester, since, pageSize)
    }

    @GetMapping("/eventinvitations/sync")
    fun syncInvitations(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<EventInvitationResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return eventService.syncInvitations(requester, since, pageSize)
    }

    @GetMapping("/eventoccurrences/sync")
    fun syncOccurrences(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<EventOccurrenceResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return occurrenceService.syncOccurrences(requester, since, pageSize)
    }

    @GetMapping("/eventoccurrenceanswers/sync")
    fun syncOccurrenceAnswers(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<EventOccurrenceAnswerResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return occurrenceService.syncAnswers(requester, since, pageSize)
    }
}
