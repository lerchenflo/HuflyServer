package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Times are stored as Long, so a Mongo TTL index cannot expire sessions; this job does instead. */
@Component
class RefreshTokenCleanup(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    fun removeExpiredSessions() {
        refreshTokenRepository.deleteByExpiresAtBefore(clock.millis())
    }
}
