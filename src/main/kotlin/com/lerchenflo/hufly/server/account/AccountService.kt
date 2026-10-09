package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.account.model.AccountResponse
import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.account.model.OwnJoinRequestResponse
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.core.MILLIS_PER_DAY
import com.lerchenflo.hufly.server.core.notification.JoinRequested
import com.lerchenflo.hufly.server.core.security.LoginGuard
import com.lerchenflo.hufly.server.repository.JoinRequestRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.InviteCodes
import com.lerchenflo.hufly.server.stable.StableOnboardingService
import com.lerchenflo.hufly.server.stable.model.Stable
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/** What an account does for itself before (and besides) being in a stable: its join request and an own stable. */
@Service
class AccountService(
    private val userRepository: UserRepository,
    private val stableRepository: StableRepository,
    private val joinRequestRepository: JoinRequestRepository,
    private val onboarding: StableOnboardingService,
    private val loginGuard: LoginGuard,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun me(account: Account) = AccountResponse(
        id = account.id.toHexString(),
        email = account.email,
        displayName = account.displayName,
        mustChangePassword = account.mustChangePassword,
        joinRequest = joinRequestRepository.findFirstByAccountIdOrderByCreatedAtDesc(account.id)
            ?.takeIf { it.status == JoinRequestStatus.PENDING || it.status == JoinRequestStatus.DECLINED }
            ?.let { request -> stableRepository.findById(request.stableId)?.takeUnless { it.deleted }?.let { toOwnResponse(request, it) } },
    )

    /**
     * Asks the stable with invite code [codeInput] to take the account in, replacing an earlier request. Wrong codes
     * count per account (429 after too many), so codes cannot be guessed. The admin hears about it by push, but only
     * once a day per stable, so asking again after a decline does not spam them.
     */
    fun requestToJoin(account: Account, codeInput: String): OwnJoinRequestResponse {
        if (userRepository.findFirstByAccountIdAndDeletedFalse(account.id) != null) throw StableOnboardingService.alreadyMember()
        val key = account.id.toHexString()
        loginGuard.checkJoinCode(key)
        val stable = InviteCodes.normalize(codeInput)?.let(stableRepository::findByInviteCodeAndDeletedFalse)
        if (stable == null) {
            loginGuard.joinCodeFailed(key)
            throw CodedException(HttpStatus.NOT_FOUND, INVALID_CODE, "Unknown invite code")
        }
        val now = clock.millis()
        val askedRecently = joinRequestRepository.findByAccountIdAndStableId(account.id, stable.id).any { it.createdAt > now - MILLIS_PER_DAY }
        val request = JoinRequest(accountId = account.id, stableId = stable.id, status = JoinRequestStatus.PENDING, createdAt = now, updatedAt = now)
        withdrawPending(account)
        val saved = try {
            joinRequestRepository.insert(request)
        } catch (e: DuplicateKeyException) {
            // A parallel request won the race; this one replaces it, as the later call would anyway.
            withdrawPending(account)
            joinRequestRepository.insert(request)
        }
        if (!askedRecently) events.publishEvent(JoinRequested(stable.id, account.id, saved.id))
        return toOwnResponse(saved, stable)
    }

    fun withdrawJoinRequest(account: Account) = withdrawPending(account)

    fun createOwnStable(account: Account, name: String, place: String?) = onboarding.createStableForAccount(account, name, place)

    private fun withdrawPending(account: Account) {
        joinRequestRepository.findByAccountIdAndStatus(account.id, JoinRequestStatus.PENDING).forEach {
            joinRequestRepository.save(it.copy(status = JoinRequestStatus.WITHDRAWN, updatedAt = clock.millis()))
        }
    }

    private fun toOwnResponse(request: JoinRequest, stable: Stable) =
        OwnJoinRequestResponse(
            id = request.id.toHexString(),
            stableId = stable.id.toHexString(),
            stableName = stable.name,
            stablePlace = stable.place,
            status = request.status,
            createdAt = request.createdAt,
        )

    companion object {
        const val INVALID_CODE = "INVALID_CODE"
    }
}
