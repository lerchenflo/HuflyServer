package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_MINUTE
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

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
    private val window = 15 * MILLIS_PER_MINUTE
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

    /** Milliseconds until [ip] may try the operator login again, or null. */
    fun operatorRetryAfter(ip: String): Long? = operatorByIp.retryAfter(ip)

    fun operatorLoginFailed(ip: String) = operatorByIp.recordFailure(ip)
}
