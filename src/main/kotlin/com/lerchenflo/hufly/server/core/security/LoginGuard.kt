package com.lerchenflo.hufly.server.core.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Brute-force protection for password logins. Only failures count, because many members share the stable's
 * network and therefore one IP.
 */
@Component
class LoginGuard(
    @Value("\${ratelimit.login.max-failures-per-email:10}") maxFailuresPerEmail: Int,
    @Value("\${ratelimit.login.max-failures-per-ip:30}") maxFailuresPerIp: Int,
    @Value("\${ratelimit.operator.max-failures-per-ip:5}") maxOperatorFailuresPerIp: Int,
    clock: Clock,
) {
    private val window = Duration.ofMinutes(15)
    private val byEmail = FailureLimiter(maxFailuresPerEmail, window, clock)
    private val byIp = FailureLimiter(maxFailuresPerIp, window, clock)
    private val operatorByIp = FailureLimiter(maxOperatorFailuresPerIp, window, clock)

    fun checkLogin(email: String, ip: String) {
        val blocked = listOfNotNull(byEmail.retryAfter(email), byIp.retryAfter(ip)).maxOrNull()
        if (blocked != null) throw TooManyAttemptsException(blocked)
    }

    fun loginFailed(email: String, ip: String) {
        byEmail.recordFailure(email)
        byIp.recordFailure(ip)
    }

    fun loginSucceeded(email: String) = byEmail.reset(email)

    fun operatorRetryAfter(ip: String): Duration? = operatorByIp.retryAfter(ip)

    fun operatorLoginFailed(ip: String) = operatorByIp.recordFailure(ip)
}
