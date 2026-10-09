package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.account.model.JoinRequestResponse
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.user.model.UserResponse
import com.lerchenflo.hufly.server.user.model.toUserResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The stable admin answers join requests (admin only). */
@RestController
@RequestMapping("/stables/me/joinrequests")
class JoinRequestController(
    private val accessService: AccessService,
    private val joinRequestService: JoinRequestService,
) {
    data class AcceptRequest(@field:Size(max = 50) val roleTagIds: List<String> = emptyList())

    @GetMapping
    fun pending(): List<JoinRequestResponse> = joinRequestService.pending(accessService.requester(requireAuth()))

    @PostMapping("/{requestId}/accept")
    fun accept(@PathVariable requestId: String, @Valid @RequestBody(required = false) request: AcceptRequest?): UserResponse {
        val requester = accessService.requester(requireAuth())
        val roleTagIds = (request ?: AcceptRequest()).roleTagIds.map(::parseObjectId)
        return joinRequestService.accept(requester, parseObjectId(requestId), roleTagIds).toUserResponse()
    }

    @PostMapping("/{requestId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun decline(@PathVariable requestId: String) =
        joinRequestService.decline(accessService.requester(requireAuth()), parseObjectId(requestId))
}
