package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.tag.model.TagResponse
import com.lerchenflo.hufly.server.tag.model.toTagResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/tags")
class TagController(
    private val accessService: AccessService,
    private val tagRepository: TagRepository,
) {

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
}
