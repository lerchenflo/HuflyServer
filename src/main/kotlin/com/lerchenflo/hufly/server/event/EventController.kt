package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.event.model.EventInvitationResponse
import com.lerchenflo.hufly.server.event.model.EventResponse
import com.lerchenflo.hufly.server.event.model.toEventInvitationResponse
import com.lerchenflo.hufly.server.event.model.toEventResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
class EventController(
    private val accessService: AccessService,
    private val eventService: EventService,
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
    )

    data class InviteRequest(@field:Size(min = 1, max = 500) val userIds: List<String>)

    data class AnswerRequest(val accepted: Boolean)

    @PostMapping("/events")
    fun createEvent(@Valid @RequestBody request: EventRequest): EventResponse {
        val requester = accessService.requester(requireAuth())
        return eventService.createEvent(
            requester, request.title, request.description, Instant.ofEpochMilli(request.startAt), Instant.ofEpochMilli(request.endAt),
            request.horseIds.map(::parseObjectId), request.inviteeUserIds.map(::parseObjectId),
        ).toEventResponse()
    }

    @PutMapping("/events/{eventId}")
    fun updateEvent(@PathVariable eventId: String, @Valid @RequestBody request: EventRequest): EventResponse {
        val requester = accessService.requester(requireAuth())
        return eventService.updateEvent(
            requester, parseObjectId(eventId), request.title, request.description, Instant.ofEpochMilli(request.startAt),
            Instant.ofEpochMilli(request.endAt), request.horseIds.map(::parseObjectId),
        ).toEventResponse()
    }

    @DeleteMapping("/events/{eventId}")
    fun deleteEvent(@PathVariable eventId: String) {
        val requester = accessService.requester(requireAuth())
        eventService.deleteEvent(requester, parseObjectId(eventId))
    }

    @PostMapping("/events/{eventId}/invitations")
    fun invite(@PathVariable eventId: String, @Valid @RequestBody request: InviteRequest) {
        val requester = accessService.requester(requireAuth())
        eventService.invite(requester, parseObjectId(eventId), request.userIds.map(::parseObjectId))
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
}
