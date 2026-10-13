package com.lerchenflo.hufly.server.core.access

import com.lerchenflo.hufly.server.core.badRequest
import org.bson.types.ObjectId
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

const val STABLE_HEADER = "X-Stable-Id"

/** The stable the current request asks for (header [STABLE_HEADER]); null without one or outside a request, 400 if malformed. */
fun requestedStableId(): ObjectId? {
    val value = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader(STABLE_HEADER)
        ?: return null
    if (!ObjectId.isValid(value)) throw badRequest("Invalid $STABLE_HEADER")
    return ObjectId(value)
}
