package com.lerchenflo.hufly.server.core.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
) {

    /**
     * Only the website operator, from OPERATOR_USERNAME / OPERATOR_PASSWORD; app users authenticate via [JwtAuthFilter].
     * Without both, or with a password under 12 characters, there is no operator and the operator area stays closed.
     */
    @Bean
    fun userDetailsService(
        @Value("\${operator.username:}") username: String,
        @Value("\${operator.password:}") password: String,
    ): UserDetailsService {
        if (username.isBlank() || password.length < 12) return InMemoryUserDetailsManager()
        val operator = User.withUsername(username)
            .password("{bcrypt}" + BCryptPasswordEncoder().encode(password))
            .roles(OPERATOR_ROLE)
            .build()
        return InMemoryUserDetailsManager(operator)
    }

    /** The operator area lives under an unguessable OPERATOR_PATH and needs HTTP Basic; app tokens never carry the role. */
    @Bean
    @Order(1)
    fun operatorFilterChain(
        http: HttpSecurity,
        @Value("\${operator.path}") operatorPath: String,
        loginGuard: LoginGuard,
    ): SecurityFilterChain {
        val basicEntryPoint = BasicAuthenticationEntryPoint().apply { setRealmName("Hufly"); afterPropertiesSet() }
        // Wrong Basic credentials end here; a request without credentials is only the browser's first try.
        val countingEntryPoint = AuthenticationEntryPoint { request, response, exception ->
            if (request.getHeader("Authorization")?.startsWith("Basic ") == true) loginGuard.operatorLoginFailed(request.remoteAddr)
            basicEntryPoint.commence(request, response, exception)
        }
        val blockedIps = OncePerRequestFilterAdapter { request, response, chain ->
            val retryAfter = loginGuard.operatorRetryAfter(request.remoteAddr)
            if (retryAfter == null) {
                chain.doFilter(request, response)
            } else {
                response.setHeader("Retry-After", retryAfterSeconds(retryAfter).toString())
                response.sendError(HttpStatus.TOO_MANY_REQUESTS.value())
            }
        }
        return http
            .securityMatcher(requireValidOperatorPath(operatorPath), "$operatorPath/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().hasRole(OPERATOR_ROLE) }
            .httpBasic { it.authenticationEntryPoint(countingEntryPoint) }
            .exceptionHandling { it.authenticationEntryPoint(countingEntryPoint) }
            .addFilterBefore(blockedIps, BasicAuthenticationFilter::class.java)
            .build()
    }

    @Bean
    @Order(2)
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { auth ->
            auth
                .requestMatchers("/auth/**").permitAll()
                // Public sales website from resources/static
                .requestMatchers("/", "/index.html", "/impressum.html", "/datenschutz.html", "/konto-loeschen.html", "/assets/**", "/favicon.svg").permitAll()
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated()
        }
        .exceptionHandling { it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) }
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()
}

const val OPERATOR_ROLE = "OPERATOR"

/** One path segment of at least 4 safe characters; anything else (an empty OPERATOR_PATH, "/", wildcards) stops startup. */
fun requireValidOperatorPath(path: String): String {
    check(Regex("^/[A-Za-z0-9_-]{4,}$").matches(path)) { "OPERATOR_PATH must look like /hb-7f3k2q, got '$path'" }
    return path
}

/** A plain filter from a lambda; not a bean, so it only runs inside the chain it is added to. */
private class OncePerRequestFilterAdapter(
    private val body: (HttpServletRequest, HttpServletResponse, FilterChain) -> Unit,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) =
        body(request, response, filterChain)
}
