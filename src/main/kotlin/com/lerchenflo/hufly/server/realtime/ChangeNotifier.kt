package com.lerchenflo.hufly.server.realtime

import com.fasterxml.jackson.annotation.JsonInclude
import com.lerchenflo.hufly.server.repository.UserRepository
import org.bson.types.ObjectId
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.stereotype.Service
import org.springframework.web.socket.messaging.SessionConnectedEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells connected apps which collection changed; they then run their normal sync, so all visibility rules stay in
 * the sync endpoints. Hints carry no data. Delivery goes to each user's `/user/queue/changes`.
 */
@Service
class ChangeNotifier(
    private val messagingTemplate: SimpMessagingTemplate,
    private val userRegistry: SimpUserRegistry,
    private val userRepository: UserRepository,
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChangeHint(val type: String = "changed", val collection: String, val originSessionId: String? = null)

    /** User id -> stable id of connected users, filled on connect so sending needs no database lookups. */
    private val stableOfUser = ConcurrentHashMap<String, ObjectId>()

    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.takeIf(ObjectId::isValid) ?: return
        userRepository.findById(ObjectId(userId))?.let { stableOfUser[userId] = it.stableId }
    }

    fun send(target: HintTarget, collection: String, originSessionId: ObjectId?) {
        val hint = ChangeHint(collection = collection, originSessionId = originSessionId?.toHexString())
        val userNames = when (target) {
            is HintTarget.Stable -> userRegistry.users.map { it.name }.filter { stableOfUser[it] == target.stableId }
            is HintTarget.User -> listOf(target.userId.toHexString()).filter { userRegistry.getUser(it) != null }
        }
        userNames.forEach { messagingTemplate.convertAndSendToUser(it, "/queue/changes", hint) }
    }
}

sealed interface HintTarget {
    /** Every connected member of the stable. */
    data class Stable(val stableId: ObjectId) : HintTarget
    /** Only the user's own devices. */
    data class User(val userId: ObjectId) : HintTarget
}
