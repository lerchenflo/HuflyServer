package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.currentSessionId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** The push token of the calling install, stored on its session (the access token's `sid`). */
@RestController
@RequestMapping("/users/me/pushtoken")
class PushTokenController(
    private val accessService: AccessService,
    private val pushService: PushService,
) {
    data class PushTokenRequest(
        val platform: PushPlatform,
        @field:NotBlank @field:Size(max = 4096) val token: String,
    )

    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun register(@Valid @RequestBody request: PushTokenRequest) {
        val requester = accessService.requester(requireAuth())
        pushService.register(requester.id, requireSession(), request.platform, request.token)
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unregister() {
        val requester = accessService.requester(requireAuth())
        pushService.unregister(requester.id, requireSession())
    }

    private fun requireSession() = currentSessionId() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Token without session")
}
