package com.lerchenflo.hufly.server.operator

import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.OPERATOR_ROLE
import com.lerchenflo.hufly.server.core.security.generatePassword
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.StableDeletionService
import com.lerchenflo.hufly.server.stable.StableOnboardingService
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Operator website: creates stables with their first admin and deletes them with all their data. Lives under OPERATOR_PATH behind HTTP Basic
 * (see `SecurityConfig.operatorFilterChain`). The page is not in `static/`, so it only exists under that path.
 */
@RestController
@RequestMapping("\${operator.path}")
class OperatorController(
    private val onboarding: StableOnboardingService,
    private val deletion: StableDeletionService,
    private val stableRepository: StableRepository,
    private val userRepository: UserRepository,
) {

    data class CreateStableRequest(
        @field:NotBlank @field:Size(max = 100) val stableName: String,
        @field:NotBlank @field:Email @field:Size(max = 254) val adminEmail: String,
        @field:NotBlank @field:Size(max = 100) val adminDisplayName: String,
    )

    data class DeleteStableRequest(@field:NotBlank @field:Size(max = 100) val confirmName: String)

    data class CreatedStableResponse(val stableId: String, val stableName: String, val adminEmail: String, val generatedPassword: String)

    data class StableSummary(
        val id: String,
        val name: String,
        val adminEmail: String?,
        val subscriptionStatus: SubscriptionStatus,
        val createdAt: Long,
    )

    @GetMapping(produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): Resource {
        requireOperator()
        return ClassPathResource("operator/index.html")
    }

    @GetMapping("/api/stables")
    fun listStables(): List<StableSummary> {
        requireOperator()
        return stableRepository.findByDeletedFalse().sortedByDescending { it.createdAt }.map { stable ->
            StableSummary(
                id = stable.id.toHexString(),
                name = stable.name,
                adminEmail = userRepository.findById(stable.adminUserId)?.email,
                subscriptionStatus = stable.subscriptionStatus,
                createdAt = stable.createdAt,
            )
        }
    }

    /** JSON only: a form post from another website cannot reuse the browser's cached Basic credentials. */
    @PostMapping("/api/stables", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun createStable(@Valid @RequestBody request: CreateStableRequest): CreatedStableResponse {
        requireOperator()
        val password = generatePassword()
        val created = onboarding.createStable(request.stableName, request.adminEmail, request.adminDisplayName, password)
        return CreatedStableResponse(created.stable.id.toHexString(), created.stable.name, created.admin.email, password)
    }

    /** Deletes the stable with all its data; JSON only like [createStable]. */
    @DeleteMapping("/api/stables/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteStable(@PathVariable id: String, @Valid @RequestBody request: DeleteStableRequest) {
        requireOperator()
        deletion.deleteStable(parseObjectId(id), request.confirmName)
    }

    /** Second line of defense in case a request reaches this controller outside the operator filter chain. */
    private fun requireOperator() {
        val authorities = SecurityContextHolder.getContext().authentication?.authorities.orEmpty()
        if (authorities.none { it.authority == "ROLE_$OPERATOR_ROLE" }) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}
