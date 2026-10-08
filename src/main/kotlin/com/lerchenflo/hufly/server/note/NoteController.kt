package com.lerchenflo.hufly.server.note

import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.MAX_EPOCH_DAYS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.note.model.NoteResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/notes")
class NoteController(
    private val accessService: AccessService,
    private val noteService: NoteService,
) {

    data class NoteEditRequest(
        @field:NotBlank @field:Size(max = MAX_TITLE_LENGTH) val title: String,
        @field:Size(max = 5000) val body: String = "",
        val pinned: Boolean = false,
        @field:Min(-MAX_EPOCH_DAYS) @field:Max(MAX_EPOCH_DAYS) val visibleUntil: Long? = null,
        /** Only read on create. */
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
    ) {
        fun toData() = NoteService.NoteData(title, body, pinned, visibleUntil)
    }

    @PostMapping
    fun createNote(@Valid @RequestBody request: NoteEditRequest): NoteResponse {
        val requester = accessService.requester(requireAuth())
        return noteService.toResponse(requester, noteService.createNote(requester, request.toData(), request.clientId))
    }

    @PutMapping("/{noteId}")
    fun updateNote(@PathVariable noteId: String, @Valid @RequestBody request: NoteEditRequest): NoteResponse {
        val requester = accessService.requester(requireAuth())
        return noteService.toResponse(requester, noteService.updateNote(requester, parseObjectId(noteId), request.toData()))
    }

    @DeleteMapping("/{noteId}")
    fun deleteNote(@PathVariable noteId: String) {
        val requester = accessService.requester(requireAuth())
        noteService.deleteNote(requester, parseObjectId(noteId))
    }

    /** The client sends `{}`; any body is ignored. */
    @PostMapping("/{noteId}/read")
    fun markRead(@PathVariable noteId: String): NoteResponse {
        val requester = accessService.requester(requireAuth())
        return noteService.toResponse(requester, noteService.markRead(requester, parseObjectId(noteId)))
    }

    @GetMapping("/sync")
    fun sync(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<NoteResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return noteService.sync(requester, since, pageSize)
    }
}
