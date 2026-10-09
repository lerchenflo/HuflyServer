package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestResponse
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.notification.JoinAccepted
import com.lerchenflo.hufly.server.realtime.ChangeListener
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.JoinRequestRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.StableOnboardingService
import com.lerchenflo.hufly.server.user.UserService
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** The stable admin's side of join requests. Accept and decline claim the PENDING request atomically. */
@Service
class JoinRequestService(
    private val joinRequestRepository: JoinRequestRepository,
    private val accountRepository: AccountRepository,
    private val userRepository: UserRepository,
    private val userService: UserService,
    private val accessService: AccessService,
    private val changeListener: ChangeListener,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun pending(requester: User): List<JoinRequestResponse> {
        accessService.requireAdmin(requester)
        return joinRequestRepository.findByStableIdAndStatus(requester.stableId, JoinRequestStatus.PENDING)
            .sortedBy { it.createdAt }
            .mapNotNull { request ->
                val account = accountRepository.findById(request.accountId)?.takeUnless { it.deleted } ?: return@mapNotNull null
                JoinRequestResponse(request.id.toHexString(), account.id.toHexString(), account.displayName, account.email, request.createdAt)
            }
    }

    /** The account becomes a member with [roleTagIds]; 409 `ALREADY_MEMBER` if it got a stable meanwhile. */
    fun accept(requester: User, requestId: ObjectId, roleTagIds: List<ObjectId>): User {
        accessService.requireAdmin(requester)
        userService.requireRoleTags(requester.stableId, roleTagIds)
        val request = claim(requester, requestId, JoinRequestStatus.ACCEPTED)
        val account = accountRepository.findById(request.accountId)?.takeUnless { it.deleted }
        if (account == null) {
            resolve(request, JoinRequestStatus.WITHDRAWN)
            throw notFound()
        }
        val now = clock.millis()
        val member = try {
            userRepository.insert(
                User(
                    stableId = requester.stableId,
                    email = account.email,
                    displayName = account.displayName,
                    phoneNumber = null,
                    profilePictureUrl = null,
                    roleTagIds = roleTagIds,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                    accountId = account.id,
                )
            )
        } catch (e: DuplicateKeyException) {
            resolve(request, JoinRequestStatus.WITHDRAWN)
            throw StableOnboardingService.alreadyMember()
        }
        events.publishEvent(JoinAccepted(requester.stableId, requester.id, member.id))
        return member
    }

    fun decline(requester: User, requestId: ObjectId) {
        accessService.requireAdmin(requester)
        claim(requester, requestId, JoinRequestStatus.DECLINED)
    }

    /** 404 for unknown requests, those of other stables and those no longer PENDING. */
    private fun claim(requester: User, requestId: ObjectId, status: JoinRequestStatus): JoinRequest {
        val now = clock.millis()
        if (joinRequestRepository.resolvePending(requestId, requester.stableId, status, now) != 1L) throw notFound()
        val claimed = joinRequestRepository.findById(requestId) ?: throw notFound()
        // The atomic update fires no Mongo save event, so announce it like one for the realtime hints.
        changeListener.publish(claimed)
        return claimed
    }

    private fun resolve(request: JoinRequest, status: JoinRequestStatus) {
        joinRequestRepository.save(request.copy(status = status, updatedAt = clock.millis()))
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Join request not found")
}
