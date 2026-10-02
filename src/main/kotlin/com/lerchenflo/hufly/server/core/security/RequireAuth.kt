package com.lerchenflo.hufly.server.core.security

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

/** The authenticated user's id; 401 also for the anonymous principal of public paths. First call in every handler. */
fun requireAuth(): ObjectId =
    (SecurityContextHolder.getContext().authentication?.principal as? String)
        ?.takeIf(ObjectId::isValid)?.let(::ObjectId)
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in")

/** The device session of the current access token, set by [JwtAuthFilter]; null for tokens without one. */
fun currentSessionId(): ObjectId? =
    SecurityContextHolder.getContext().authentication?.details as? ObjectId
