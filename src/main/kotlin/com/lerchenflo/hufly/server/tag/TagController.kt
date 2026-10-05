package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagResponse
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.tag.model.toTagResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tags")
class TagController(
    private val accessService: AccessService,
    private val tagRepository: TagRepository,
    private val tagService: TagService,
) {

    data class CreateTagRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        val type: TagType,
        @field:Pattern(regexp = COLOR_PATTERN) val color: String,
        val permissions: Set<Permission> = emptySet(),
        @field:Min(1) @field:Max(MAX_INTERVAL_DAYS) val defaultIntervalDays: Int? = null,
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
        @field:Pattern(regexp = ICON_PATTERN, message = "Invalid tag icon") val icon: String? = null,
    )

    data class UpdateTagRequest(
        @field:NotBlank @field:Size(max = 50) val name: String,
        @field:Pattern(regexp = COLOR_PATTERN) val color: String,
        val permissions: Set<Permission> = emptySet(),
        @field:Min(1) @field:Max(MAX_INTERVAL_DAYS) val defaultIntervalDays: Int? = null,
        /** Null clears the icon. */
        @field:Pattern(regexp = ICON_PATTERN, message = "Invalid tag icon") val icon: String? = null,
    )

    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<TagResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            tagRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toTagResponse() },
        )
    }

    @PostMapping
    fun createTag(@Valid @RequestBody request: CreateTagRequest): TagResponse {
        val requester = accessService.requester(requireAuth())
        return tagService.createTag(requester, request.name, request.type, request.color, request.permissions, request.clientId, request.defaultIntervalDays, request.icon).toTagResponse()
    }

    @PutMapping("/{tagId}")
    fun updateTag(@PathVariable tagId: String, @Valid @RequestBody request: UpdateTagRequest): TagResponse {
        val requester = accessService.requester(requireAuth())
        return tagService.updateTag(requester, parseObjectId(tagId), request.name, request.color, request.permissions, request.defaultIntervalDays, request.icon).toTagResponse()
    }

    @DeleteMapping("/{tagId}")
    fun deleteTag(@PathVariable tagId: String) {
        val requester = accessService.requester(requireAuth())
        tagService.deleteTag(requester, parseObjectId(tagId))
    }
}

private const val MAX_INTERVAL_DAYS = 3650L
private const val COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$"
private const val ICON_PATTERN = "^[a-z_]{1,40}$"
