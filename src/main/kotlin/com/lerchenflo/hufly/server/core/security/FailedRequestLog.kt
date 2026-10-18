package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.CodedException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.boot.webmvc.error.ErrorAttributes
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.BindException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Logs every failed app request (status 400 and up, except 401 for missing or expired logins) with its method, path,
 * cause, the allowed methods of a 405 and the app (the account is in the line's user column), so a failure seen in the
 * log can be traced. Never logs bodies, query strings or rejected values; they may hold passwords or names. Runs after
 * [JwtAuthFilter]. Wrong paths, methods or types without a login go to DEBUG: those are bots probing the public website.
 */
class FailedRequestLog(private val errorAttributes: ErrorAttributes) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        try {
            filterChain.doFilter(request, response)
        } finally {
            val status = response.status
            if (status >= 400 && status != 401) logFailure(request, response, status)
        }
    }

    private fun logFailure(request: HttpServletRequest, response: HttpServletResponse, status: Int) {
        val cause = cause(errorAttributes.getError(ServletWebRequest(request)))
        val accountId = (SecurityContextHolder.getContext().authentication?.principal as? String)?.takeIf(ObjectId::isValid)
        val details = listOfNotNull(
            response.getHeader("Allow")?.let { allow -> "allowed " + allow.split(",").map(String::trim).sorted().joinToString(",") },
            "agent ${request.getHeader("User-Agent")?.let { clean(it, 100) } ?: "none"}",
        ).joinToString("; ")
        val message = "$status ${request.method} ${clean(request.requestURI, 200)}${cause?.let { ": $it" }.orEmpty()} ($details)"
        when {
            status >= 500 -> log.warn(message)
            // Without a login only the website and /auth/** are reachable; wrong paths there are bots probing for files.
            accountId == null && status in BOT_PROBE_STATUSES -> log.debug(message)
            else -> log.info(message)
        }
    }

    /** Only texts the server wrote itself; validation names the fields, not what was sent. */
    private fun cause(error: Throwable?): String? = when (error) {
        null -> null
        is CodedException -> "${error.code} ${error.reason}"
        is ResponseStatusException -> error.reason ?: error.statusCode.toString()
        is BindException -> "invalid " + error.bindingResult.fieldErrors.map { it.field }.distinct().joinToString(",")
        is HttpRequestMethodNotSupportedException, is HttpMediaTypeNotSupportedException, is NoResourceFoundException -> error.message
        else -> error.javaClass.simpleName
    }?.let { clean(it, 200) }

    /** Against forged log lines and floods. */
    private fun clean(text: String, max: Int) = text.replace(Regex("\\p{Cntrl}"), "?").take(max)

    private companion object {
        /** Not found, method not allowed, not acceptable. */
        val BOT_PROBE_STATUSES = setOf(404, 405, 406)
    }
}
