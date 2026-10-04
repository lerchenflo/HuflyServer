package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.core.security.currentSessionId
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bson.types.ObjectId
import org.springframework.core.MethodParameter
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice

/**
 * Hints of saves made while answering an HTTP request wait until the request is done. Then a hint names the
 * requesting session (`originSessionId`) only when the request answered that very document, so the requesting device
 * can skip its pull. Answered are: the ids of a 2xx response body (or of its list elements), the path ids of a
 * successful DELETE, and ids registered with [markAnswered]. Everything else (cascades, version bumps) gets no origin.
 */
@Component
class ChangeOriginFilter(private val notifier: ChangeNotifier) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val state = RequestState()
        request.setAttribute(STATE_ATTRIBUTE, state)
        try {
            filterChain.doFilter(request, response)
        } finally {
            request.removeAttribute(STATE_ATTRIBUTE)
            val succeeded = response.status in 200..299
            if (succeeded && request.method == HttpMethod.DELETE.name()) {
                @Suppress("UNCHECKED_CAST")
                (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>)
                    ?.values?.let(state.answered::addAll)
            }
            state.pending.forEach { hint ->
                val origin = hint.sessionId?.takeIf { succeeded && hint.documentId in state.answered }
                notifier.send(hint.target, hint.collection, origin)
            }
        }
    }

    internal class RequestState {
        val pending = mutableListOf<PendingHint>()
        val answered = mutableSetOf<String>()
    }

    internal data class PendingHint(val target: HintTarget, val collection: String, val documentId: String, val sessionId: ObjectId?)
}

@ControllerAdvice
class ChangeOriginResponseAdvice : ResponseBodyAdvice<Any> {
    override fun supports(returnType: MethodParameter, converterType: Class<out HttpMessageConverter<*>>) = true

    override fun beforeBodyWrite(
        body: Any?,
        returnType: MethodParameter,
        selectedContentType: MediaType,
        selectedConverterType: Class<out HttpMessageConverter<*>>,
        request: ServerHttpRequest,
        response: ServerHttpResponse,
    ): Any? {
        when (body) {
            is Collection<*> -> body.forEach { it?.let(::idOf)?.let(::markAnswered) }
            null -> Unit
            else -> idOf(body)?.let(::markAnswered)
        }
        return body
    }

    // Every response DTO exposes its document id as `id: String`.
    private fun idOf(body: Any): String? = runCatching {
        body.javaClass.getMethod("getId").invoke(body) as? String
    }.getOrNull()
}

/** Marks a document as part of the current request's answer, for responses whose body carries no `id`. */
fun markAnswered(documentId: Any) {
    currentState()?.answered?.add(documentId.toString())
}

/** Sends the hint now outside a request, else queues it until [ChangeOriginFilter] knows the request's answer. */
internal fun queueOrSend(notifier: ChangeNotifier, target: HintTarget, collection: String, documentId: Any) {
    val state = currentState()
    if (state == null) {
        notifier.send(target, collection, null)
    } else {
        state.pending += ChangeOriginFilter.PendingHint(target, collection, documentId.toString(), currentSessionId())
    }
}

private fun currentState(): ChangeOriginFilter.RequestState? =
    (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
        ?.request?.getAttribute(STATE_ATTRIBUTE) as? ChangeOriginFilter.RequestState

private val STATE_ATTRIBUTE = ChangeOriginFilter::class.java.name + ".state"
