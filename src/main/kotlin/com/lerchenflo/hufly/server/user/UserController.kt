package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.stable.StableLookupService
import com.lerchenflo.hufly.server.stable.model.toStableResponse
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.user.model.MeResponse
import com.lerchenflo.hufly.server.user.model.UserResponse
import com.lerchenflo.hufly.server.user.model.toUserResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController(
    private val accessService: AccessService,
    private val stableLookupService: StableLookupService,
    private val userRepository: UserRepository,
) {

    @GetMapping("/me")
    fun me(): MeResponse {
        val requester = accessService.requester(requireAuth())
        return MeResponse(
            user = requester.toUserResponse(),
            isAdmin = accessService.isAdmin(requester),
            stable = stableLookupService.getById(requester.stableId).toStableResponse(),
            permissions = accessService.effectivePermissions(requester),
        )
    }

    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<UserResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            userRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toUserResponse() },
        )
    }
}
