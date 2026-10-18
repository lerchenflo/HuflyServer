package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.logging.LOG_USER
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
        val userId = token?.let(jwtService::userIdFromAccessToken)
        if (userId == null) {
            filterChain.doFilter(request, response)
            return
        }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId.toHexString(), null, emptyList())
                .apply { details = jwtService.sessionIdFromAccessToken(token) }
        // Every log line of the request names the account (user column of logback-spring.xml)
        MDC.putCloseable(LOG_USER, userId.toHexString()).use { filterChain.doFilter(request, response) }
    }
}
