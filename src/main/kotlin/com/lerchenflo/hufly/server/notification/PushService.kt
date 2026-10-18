package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.logging.LogUser
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.notification.sender.PlatformPushSender
import com.lerchenflo.hufly.server.notification.sender.PushResult
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.Executor

/** Push tokens sit on the session row, so logout, ending a session and expiry drop them with it. */
@Service
class PushService(
    private val sessionRepository: RefreshTokenRepository,
    private val senders: List<PlatformPushSender>,
    @Qualifier(PUSH_EXECUTOR) private val executor: Executor,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun register(userId: ObjectId, sessionId: ObjectId, platform: PushPlatform, token: String) {
        ownSession(userId, sessionId)
        sessionRepository.clearPushTokenElsewhere(token, sessionId)
        sessionRepository.setPushToken(sessionId, token, platform)
    }

    fun unregister(userId: ObjectId, sessionId: ObjectId) {
        ownSession(userId, sessionId)
        sessionRepository.clearPushTokenOfSession(sessionId)
    }

    /** Looks the devices up now and sends in the background, so a slow push service never holds a request. */
    /** [userId] is the recipient's account id. */
    fun send(userId: ObjectId, message: PushMessage) {
        val now = clock.millis()
        val targets = sessionRepository.findByUserIdAndPushTokenNotNull(userId).filter { it.expiresAt > now }
        // Never log the push's text, it holds names.
        val push = "Push ${message.data["type"]} to ${LogUser(userId)}"
        if (targets.isEmpty()) log.info("{}: no device", push)
        targets.forEach { session ->
            val token = session.pushToken ?: return@forEach
            val device = "${session.pushPlatform}, session ${session.id}"
            val sender = senders.firstOrNull { it.platform == session.pushPlatform }
                ?: return@forEach log.warn("{}: no sender ({})", push, device)
            executor.execute {
                try {
                    when (sender.send(token, message)) {
                        PushResult.SENT -> log.info("{} sent ({})", push, device)
                        PushResult.INVALID_TOKEN -> {
                            sessionRepository.clearPushToken(token)
                            log.warn("{}: token rejected ({}), removed", push, device)
                        }
                        PushResult.FAILED -> log.warn("{} failed ({})", push, device)
                    }
                } catch (e: Exception) {
                    log.warn("{} failed ({}): {}", push, device, e.message)
                }
            }
        }
    }

    private fun ownSession(userId: ObjectId, sessionId: ObjectId) {
        if (sessionRepository.findById(sessionId)?.userId != userId) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found")
    }
}

const val PUSH_EXECUTOR = "pushExecutor"
