package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.CodedException
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
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
 * Logs every failed request (status 400 and up, exceptions no handler answered as 500) with its method, path, cause,
 * the allowed methods of a 405 and the app (the account is in the line's user column), so a failure seen in the log can
 * be traced. Never logs bodies, query strings, exception messages or rejected values; they may hold passwords or names.
 * Runs after [JwtAuthFilter]. Missing or expired logins (401) and wrong paths, methods or types without a login go to
 * DEBUG: clients refresh expired tokens all the time, and the others are bots probing the public website.
 *
 * With [secretPath] (the operator area) the path is never logged, and wrong logins and the IP block they lead to go to
 * WARN.
 */
class FailedRequestLog(
    private val errorAttributes: ErrorAttributes,
    private val secretPath: Boolean = false,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        var thrown: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Throwable) {
            thrown = e
            throw e
        } finally {
            // The status is still unset here; Tomcat answers the exception with 500 later.
            val status = if (thrown != null) 500 else response.status
            if (status >= 400) logFailure(request, response, status, thrown)
        }
    }

    private fun logFailure(request: HttpServletRequest, response: HttpServletResponse, status: Int, thrown: Throwable?) {
        // The browser's first try at the operator area comes without credentials and only asks for them.
        if (secretPath && status == 401 && request.getHeader("Authorization")?.startsWith("Basic ") != true) return
        val error = errorAttributes.getError(ServletWebRequest(request)) ?: (thrown as? ServletException)?.rootCause ?: thrown
        val cause = cause(error)
        val accountId = (SecurityContextHolder.getContext().authentication?.principal as? String)?.takeIf(ObjectId::isValid)
        val details = listOfNotNull(
            response.getHeader("Allow")?.let { allow -> "allowed " + allow.split(",").map(String::trim).sorted().joinToString(",") },
            "agent ${request.getHeader("User-Agent")?.let { clean(it, 100) } ?: "none"}",
        ).joinToString("; ")
        val path = if (secretPath) "<operator path>" else clean(request.requestURI, 200)
        val message = "$status ${request.method} $path${cause?.let { ": $it" }.orEmpty()} ($details)"
        when {
            status >= 500 -> log.warn(message)
            secretPath -> if (status == 401 || status == 429) log.warn(message) else log.info(message)
            status == 401 -> log.debug(message)
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
        is HttpRequestMethodNotSupportedException, is HttpMediaTypeNotSupportedException -> error.message
        // Its message names the path.
        is NoResourceFoundException -> if (secretPath) error.javaClass.simpleName else error.message
        else -> error.javaClass.simpleName
    }?.let { clean(it, 200) }

    /** Against forged log lines and floods. */
    private fun clean(text: String, max: Int) = text.replace(Regex("\\p{Cntrl}"), "?").take(max)

    private companion object {
        /** Not found, method not allowed, not acceptable. */
        val BOT_PROBE_STATUSES = setOf(404, 405, 406)
    }
}
