package com.lerchenflo.hufly.server.core.security

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
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

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
    fun operatorFilterChain(http: HttpSecurity, @Value("\${operator.path}") operatorPath: String): SecurityFilterChain = http
        .securityMatcher(requireValidOperatorPath(operatorPath), "$operatorPath/**")
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { it.anyRequest().hasRole(OPERATOR_ROLE) }
        .httpBasic { it.realmName("Hufly") }
        .build()

    @Bean
    @Order(2)
    fun filterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { auth ->
            auth
                .requestMatchers("/auth/**").permitAll()
                // Public sales website from resources/static
                .requestMatchers("/", "/index.html", "/impressum.html", "/datenschutz.html", "/assets/**", "/favicon.svg").permitAll()
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
