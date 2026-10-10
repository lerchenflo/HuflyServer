package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.WebSocketHandlerDecorator
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Open sockets, so they can be closed when their login session ends (logout, ended or replaced session, password
 * change or reset) or the account is deleted; otherwise they would keep receiving hints until the app disconnects.
 * Sessions are hard-deleted in many places, so a sweep checks them instead of every delete path calling in.
 */
@Component
class SocketSessions(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val accountRepository: AccountRepository,
) : WebSocketHandlerDecoratorFactory {

    private val open = ConcurrentHashMap<String, WebSocketSession>()
    private val log = LoggerFactory.getLogger(javaClass)

    override fun decorate(handler: WebSocketHandler): WebSocketHandler = object : WebSocketHandlerDecorator(handler) {
        override fun afterConnectionEstablished(session: WebSocketSession) {
            open[session.id] = session
            super.afterConnectionEstablished(session)
        }

        override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
            open.remove(session.id)
            super.afterConnectionClosed(session, closeStatus)
        }
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    fun closeEnded() {
        open.values.filterNot(::stillValid).forEach { session ->
            try {
                session.close(SESSION_ENDED)
            } catch (e: Exception) {
                log.debug("Closing socket {} failed: {}", session.id, e.message)
            }
            open.remove(session.id)
        }
    }

    private fun stillValid(session: WebSocketSession): Boolean {
        val auth = session.principal as? Authentication ?: return false
        val accountId = (auth.principal as? String)?.takeIf(ObjectId::isValid)?.let(::ObjectId) ?: return false
        if (accountRepository.findById(accountId)?.deleted != false) return false
        val sessionId = auth.details as? ObjectId ?: return true
        return refreshTokenRepository.findById(sessionId)?.userId == accountId
    }

    private companion object {
        /** App-defined code: the app refreshes its token (or logs out) and reconnects. */
        val SESSION_ENDED = CloseStatus(4401, "Session ended")
    }
}
