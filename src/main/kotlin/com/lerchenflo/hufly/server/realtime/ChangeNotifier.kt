package com.lerchenflo.hufly.server.realtime

import com.fasterxml.jackson.annotation.JsonInclude
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.user.model.User
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
    private val stableRepository: StableRepository,
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChangeHint(val type: String = "changed", val collection: String, val originSessionId: String? = null)

    /**
     * Account id (the STOMP principal) -> stable id of connected accounts with a stable, filled on connect and kept
     * current when a connected account joins or leaves a stable, so sending needs no database lookups.
     */
    private val stableOfAccount = ConcurrentHashMap<String, ObjectId>()

    @EventListener
    fun onConnected(event: SessionConnectedEvent) {
        val accountId = event.user?.name?.takeIf(ObjectId::isValid) ?: return
        userRepository.findFirstByAccountIdAndDeletedFalse(ObjectId(accountId))?.let { stableOfAccount[accountId] = it.stableId }
    }

    /** A saved membership: its account now gets this stable's hints (or none, once the membership is deleted). */
    fun membershipChanged(member: User) {
        val accountId = member.accountId.toHexString()
        if (userRegistry.getUser(accountId) == null) return
        if (member.deleted) stableOfAccount.remove(accountId, member.stableId) else stableOfAccount[accountId] = member.stableId
    }

    fun send(target: HintTarget, collection: String, originSessionId: ObjectId?) {
        val hint = ChangeHint(collection = collection, originSessionId = originSessionId?.toHexString())
        val userNames = when (target) {
            is HintTarget.Stable -> userRegistry.users.map { it.name }.filter { stableOfAccount[it] == target.stableId }
            is HintTarget.Account -> listOf(target.accountId.toHexString()).filter { userRegistry.getUser(it) != null }
            is HintTarget.StableAdmin -> listOfNotNull(
                stableRepository.findById(target.stableId)?.adminUserId?.let(userRepository::findById)?.accountId?.toHexString()
            ).filter { userRegistry.getUser(it) != null }
        }
        userNames.forEach { messagingTemplate.convertAndSendToUser(it, "/queue/changes", hint) }
    }
}

sealed interface HintTarget {
    /** Every connected member of the stable. */
    data class Stable(val stableId: ObjectId) : HintTarget
    /** Only the devices of the stable admin's account. */
    data class StableAdmin(val stableId: ObjectId) : HintTarget
    /** Only the account's own devices. */
    data class Account(val accountId: ObjectId) : HintTarget
}
