package com.lerchenflo.hufly.server.realtime

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
    data class ChangeHint(val type: String = "changed", val collection: String)

    /** User id -> stable id of connected users, filled on connect so sending needs no database lookups. */
    private val stableOfUser = ConcurrentHashMap<String, ObjectId>()

    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val userId = event.user?.name?.takeIf(ObjectId::isValid) ?: return
        userRepository.findById(ObjectId(userId))?.let { stableOfUser[userId] = it.stableId }
    }

    fun notifyStable(stableId: ObjectId, collection: String) {
        userRegistry.users
            .map { it.name }
            .filter { stableOfUser[it] == stableId }
            .forEach { send(it, collection) }
    }

    fun notifyUser(userId: ObjectId, collection: String) {
        val name = userId.toHexString()
        if (userRegistry.getUser(name) != null) send(name, collection)
    }

    private fun send(userName: String, collection: String) {
        messagingTemplate.convertAndSendToUser(userName, "/queue/changes", ChangeHint(collection = collection))
    }
}
