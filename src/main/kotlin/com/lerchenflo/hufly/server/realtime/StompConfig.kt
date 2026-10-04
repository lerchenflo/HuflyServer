package com.lerchenflo.hufly.server.realtime

import org.springframework.context.annotation.Configuration
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

/**
 * STOMP over WebSocket at `/ws`. The handshake passes the normal security chain, so it needs a Bearer access token;
 * the user's id becomes the session principal. Clients only receive: they may subscribe to their own
 * `/user/queue/changes` and nothing else, and they cannot send.
 */
@Configuration
@EnableWebSocketMessageBroker
class StompConfig : WebSocketMessageBrokerConfigurer {

    // iOS clients get no WebSocket pings, so STOMP heart-beats are how they notice a dead socket.
    private val heartbeatScheduler = ThreadPoolTaskScheduler().apply {
        setPoolSize(1)
        setThreadNamePrefix("ws-heartbeat-")
        initialize()
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws")
    }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/queue")
            .setHeartbeatValue(longArrayOf(HEARTBEAT_MILLIS, HEARTBEAT_MILLIS))
            .setTaskScheduler(heartbeatScheduler)
        registry.setUserDestinationPrefix("/user")
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(ReceiveOnlyInterceptor)
    }

    private object ReceiveOnlyInterceptor : ChannelInterceptor {
        override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
            val accessor = StompHeaderAccessor.wrap(message)
            when (accessor.command) {
                StompCommand.SUBSCRIBE ->
                    if (accessor.destination != CHANGES_DESTINATION) throw MessageDeliveryException("Only $CHANGES_DESTINATION")
                StompCommand.SEND -> throw MessageDeliveryException("Clients cannot send")
                else -> Unit
            }
            return message
        }
    }
}

const val CHANGES_DESTINATION = "/user/queue/changes"
private const val HEARTBEAT_MILLIS = 10_000L
