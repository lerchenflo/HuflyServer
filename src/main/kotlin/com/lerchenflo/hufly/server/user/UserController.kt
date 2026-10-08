package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.picture.pictureResponse
import com.lerchenflo.hufly.server.core.security.currentSessionId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.stable.StableLookupService
import com.lerchenflo.hufly.server.stable.model.toStableResponse
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.user.model.CreatedUserResponse
import com.lerchenflo.hufly.server.user.model.GeneratedPasswordResponse
import com.lerchenflo.hufly.server.user.model.MeResponse
import com.lerchenflo.hufly.server.user.model.UserResponse
import com.lerchenflo.hufly.server.user.model.toUserResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

@RestController
@RequestMapping("/users")
class UserController(
    private val accessService: AccessService,
    private val stableLookupService: StableLookupService,
    private val userRepository: UserRepository,
    private val userService: UserService,
) {

    data class CreateUserRequest(
        @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
        @field:NotBlank @field:Size(max = 100) val displayName: String,
        @field:Size(max = 50) val phoneNumber: String? = null,
        @field:Size(max = 50) val roleTagIds: List<String> = emptyList(),
    )

    data class UpdateUserRequest(
        @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
        @field:NotBlank @field:Size(max = 100) val displayName: String,
        @field:Size(max = 50) val phoneNumber: String? = null,
        @field:Size(max = 50) val roleTagIds: List<String> = emptyList(),
    )

    data class UpdateMeRequest(
        @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
        @field:NotBlank @field:Size(max = 100) val displayName: String,
        @field:Size(max = 50) val phoneNumber: String? = null,
    )

    data class ChangePasswordRequest(
        @field:NotBlank @field:Size(max = 200) val oldPassword: String,
        @field:Size(min = 8, max = 200) val newPassword: String,
    )

    data class DeleteOwnAccountRequest(
        @field:NotBlank @field:Size(max = 200) val password: String,
    )

    @GetMapping("/me")
    fun me(): MeResponse {
        val requester = accessService.requester(requireAuth())
        return MeResponse(
            user = requester.toUserResponse(),
            isAdmin = accessService.isAdmin(requester),
            stable = stableLookupService.getById(requester.stableId).toStableResponse(),
            permissions = accessService.effectivePermissions(requester),
            mustChangePassword = requester.mustChangePassword,
        )
    }

    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @RequestBody clientEntries: List<IdTimeStamp>,
    ): SyncResponse<UserResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            userRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toUserResponse() },
        )
    }

    @PutMapping("/me")
    fun updateMe(@Valid @RequestBody request: UpdateMeRequest): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.updateMe(requester, request.email, request.displayName, request.phoneNumber)
            .toUserResponse()
    }

    @PutMapping("/me/picture")
    fun setMyPicture(@RequestParam("picture") picture: MultipartFile): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.setMyPicture(requester, picture.bytes).toUserResponse()
    }

    @DeleteMapping("/me/picture")
    fun deleteMyPicture(): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.deleteMyPicture(requester).toUserResponse()
    }

    @PutMapping("/{userId}/picture")
    fun setPicture(@PathVariable userId: String, @RequestParam("picture") picture: MultipartFile): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.setPicture(requester, parseObjectId(userId), picture.bytes).toUserResponse()
    }

    @DeleteMapping("/{userId}/picture")
    fun deletePicture(@PathVariable userId: String): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.deletePicture(requester, parseObjectId(userId)).toUserResponse()
    }

    @GetMapping("/{userId}/picture")
    fun picture(@PathVariable userId: String): ResponseEntity<ByteArray> {
        val requester = accessService.requester(requireAuth())
        return pictureResponse(userService.picture(requester, parseObjectId(userId)))
    }

    @PostMapping("/me/password")
    fun changePassword(@Valid @RequestBody request: ChangePasswordRequest) {
        val requester = accessService.requester(requireAuth())
        userService.changePassword(requester, request.oldPassword, request.newPassword, currentSessionId())
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteOwnAccount(@Valid @RequestBody request: DeleteOwnAccountRequest) {
        val requester = accessService.requester(requireAuth())
        userService.deleteOwnAccount(requester, request.password)
    }

    @PostMapping
    fun createUser(@Valid @RequestBody request: CreateUserRequest): CreatedUserResponse {
        val requester = accessService.requester(requireAuth())
        val created = userService.createUser(
            requester, request.email, request.displayName, request.phoneNumber, request.roleTagIds.map(::parseObjectId),
        )
        return CreatedUserResponse(created.user.toUserResponse(), created.generatedPassword)
    }

    @PutMapping("/{userId}")
    fun updateUser(@PathVariable userId: String, @Valid @RequestBody request: UpdateUserRequest): UserResponse {
        val requester = accessService.requester(requireAuth())
        return userService.updateUser(
            requester, parseObjectId(userId), request.email, request.displayName, request.phoneNumber,
            request.roleTagIds.map(::parseObjectId),
        ).toUserResponse()
    }

    @DeleteMapping("/{userId}")
    fun deleteUser(@PathVariable userId: String) {
        val requester = accessService.requester(requireAuth())
        userService.deleteUser(requester, parseObjectId(userId))
    }

    @PostMapping("/{userId}/password-reset")
    fun resetPassword(@PathVariable userId: String): GeneratedPasswordResponse {
        val requester = accessService.requester(requireAuth())
        return GeneratedPasswordResponse(userService.resetPassword(requester, parseObjectId(userId)))
    }
}
