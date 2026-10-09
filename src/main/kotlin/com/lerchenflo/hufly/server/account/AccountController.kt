package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.account.model.AccountResponse
import com.lerchenflo.hufly.server.account.model.OwnJoinRequestResponse
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.user.MeService
import com.lerchenflo.hufly.server.user.model.MeResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** The requester's own login, usable without a stable: join one with an invite code or create one. */
@RestController
@RequestMapping("/accounts/me")
class AccountController(
    private val accessService: AccessService,
    private val accountService: AccountService,
    private val meService: MeService,
) {
    data class JoinRequestRequest(@field:NotBlank @field:Size(max = 50) val code: String)

    data class CreateStableRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 100) val place: String? = null,
    )

    @GetMapping
    fun me(): AccountResponse = accountService.me(accessService.requireAccount(requireAuth()))

    @PutMapping("/joinrequest")
    fun requestToJoin(@Valid @RequestBody request: JoinRequestRequest): OwnJoinRequestResponse =
        accountService.requestToJoin(accessService.requireAccount(requireAuth()), request.code)

    @DeleteMapping("/joinrequest")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun withdrawJoinRequest() = accountService.withdrawJoinRequest(accessService.requireAccount(requireAuth()))

    @PostMapping("/stable")
    @ResponseStatus(HttpStatus.CREATED)
    fun createStable(@Valid @RequestBody request: CreateStableRequest): MeResponse {
        val created = accountService.createOwnStable(accessService.requireAccount(requireAuth()), request.name, request.place)
        return meService.me(created.admin)
    }
}
