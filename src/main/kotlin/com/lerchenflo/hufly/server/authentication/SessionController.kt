package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.currentSessionId
import com.lerchenflo.hufly.server.core.security.requireAuth
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The requester's own device sessions (USR-5). */
@RestController
@RequestMapping("/users/me/sessions")
class SessionController(
    private val accessService: AccessService,
    private val authService: AuthService,
) {

    @GetMapping
    fun sessions(): List<AuthService.SessionResponse> {
        val account = accessService.requireAccount(requireAuth())
        return authService.sessions(account.id, currentSessionId())
    }

    @DeleteMapping("/{sessionId}")
    fun endSession(@PathVariable sessionId: String) {
        val account = accessService.requireAccount(requireAuth())
        authService.endSession(account.id, parseObjectId(sessionId))
    }
}
