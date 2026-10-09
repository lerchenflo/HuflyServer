package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.realtime.ChangeListener
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.stable.model.StableResponse
import com.lerchenflo.hufly.server.stable.model.toStableResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** The own stable's name, place and invite code (admin only). */
@RestController
@RequestMapping("/stables/me")
class StableController(
    private val accessService: AccessService,
    private val stableLookupService: StableLookupService,
    private val stableRepository: StableRepository,
    private val onboarding: StableOnboardingService,
    private val changeListener: ChangeListener,
    private val clock: Clock,
) {
    data class StableRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 100) val place: String? = null,
    )

    /** Normalized, without the dash; the app shows it as `XXXX-XXXX`. */
    data class InviteCodeResponse(val code: String)

    @PutMapping
    fun update(@Valid @RequestBody request: StableRequest): StableResponse {
        val requester = accessService.requester(requireAuth())
        accessService.requireAdmin(requester)
        stableRepository.setNameAndPlace(
            requester.stableId, request.name.trim(), request.place?.trim()?.takeIf { it.isNotEmpty() }, clock.millis(), requester.id,
        )
        return stableLookupService.getById(requester.stableId).also(changeListener::publish).toStableResponse()
    }

    @GetMapping("/invitecode")
    fun inviteCode(): InviteCodeResponse {
        val requester = accessService.requester(requireAuth())
        accessService.requireAdmin(requester)
        // Every stable gets a code when created (older ones by the migration); this covers anything missed.
        return InviteCodeResponse(stableLookupService.getById(requester.stableId).inviteCode ?: newCode(requester.stableId))
    }

    /** The old code stops working at once; open join requests stay. */
    @PostMapping("/invitecode")
    fun newInviteCode(): InviteCodeResponse {
        val requester = accessService.requester(requireAuth())
        accessService.requireAdmin(requester)
        return InviteCodeResponse(newCode(requester.stableId))
    }

    private fun newCode(stableId: ObjectId): String {
        repeat(5) {
            val code = onboarding.newInviteCode()
            try {
                stableRepository.setInviteCode(stableId, code)
                return code
            } catch (e: DuplicateKeyException) {
                // Taken between the check and the write; try another one.
            }
        }
        error("No free invite code found")
    }
}
