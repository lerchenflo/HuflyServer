package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.stable.StableLookupService
import com.lerchenflo.hufly.server.stable.model.toStableResponse
import com.lerchenflo.hufly.server.user.model.MeResponse
import com.lerchenflo.hufly.server.user.model.User
import org.springframework.stereotype.Service

/** `GET /users/me`, also answered after creating an own stable. */
@Service
class MeService(
    private val accessService: AccessService,
    private val stableLookupService: StableLookupService,
    private val userResponses: UserResponses,
) {
    fun me(member: User) = MeResponse(
        user = userResponses.of(member),
        isAdmin = accessService.isAdmin(member),
        stable = stableLookupService.getById(member.stableId).toStableResponse(),
        permissions = accessService.effectivePermissions(member),
        mustChangePassword = accessService.requireAccount(member.accountId).mustChangePassword,
    )
}
