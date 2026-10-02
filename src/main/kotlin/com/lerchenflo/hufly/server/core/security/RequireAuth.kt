package com.lerchenflo.hufly.server.core.security

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

/** The authenticated user's id. First call in every handler of an authenticated endpoint. */
fun requireAuth(): ObjectId =
    (SecurityContextHolder.getContext().authentication?.principal as? String)?.let(::ObjectId)
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not logged in")
