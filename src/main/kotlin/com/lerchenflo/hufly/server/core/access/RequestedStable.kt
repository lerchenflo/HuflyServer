package com.lerchenflo.hufly.server.core.access

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

const val STABLE_HEADER = "X-Stable-Id"

/** The stable the current request asks for (header [STABLE_HEADER]); null without one or outside a request, 400 if malformed. */
fun requestedStableId(): ObjectId? {
    val value = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader(STABLE_HEADER)
        ?: return null
    if (!ObjectId.isValid(value)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid $STABLE_HEADER")
    return ObjectId(value)
}
