package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.epochMillisToInstant
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceRequest
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.task.model.TaskOccurrenceResponse
import com.lerchenflo.hufly.server.task.model.TaskResponse
import com.lerchenflo.hufly.server.task.model.toTaskOccurrenceResponse
import com.lerchenflo.hufly.server.task.model.toTaskResponse
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
import java.time.Instant

@RestController
@RequestMapping("/tasks")
class TaskController(
    private val accessService: AccessService,
    private val taskService: TaskService,
    private val occurrenceService: TaskOccurrenceService,
) {

    data class TaskRequest(
        @field:NotBlank @field:Size(max = 200) val title: String,
        @field:Size(max = 5000) val comment: String = "",
        /** Epoch milliseconds, years 1970 to 2999; null for an undated task. */
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val dueAt: Long?,
        @field:Size(max = 50) val assigneeUserIds: List<String>,
        @field:Size(max = 50) val horseIds: List<String> = emptyList(),
        /** Only read on create. */
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
        /** Null makes it a single task, also on update. */
        val recurrence: RecurrenceRequest? = null,
        /** Null clears the category, also on update. */
        val categoryTagId: String? = null,
    )

    data class DoneRequest(val done: Boolean)

    /** Null fields keep the series' value. */
    data class OccurrenceRequest(
        val cancelled: Boolean,
        val title: String?,
        val comment: String?,
        val dueAt: Long?,
        @field:Size(max = 50) val horseIds: List<String>?,
        /** Null means the series' assignees. */
        @field:Size(max = 50) val assigneeUserIds: List<String>? = null,
    )

    @PostMapping
    fun createTask(@Valid @RequestBody request: TaskRequest): TaskResponse {
        val requester = accessService.requester(requireAuth())
        val dueAt = request.dueAt?.let(Instant::ofEpochMilli)
        return taskService.createTask(
            requester, request.title, request.comment, dueAt, request.assigneeUserIds.map(::parseObjectId),
            request.horseIds.map(::parseObjectId), request.clientId, request.recurrence?.toRecurrence(dueAt ?: throw repeatingTaskNeedsDate()),
            request.categoryTagId?.let(::parseObjectId),
        ).toTaskResponse()
    }

    @PutMapping("/{taskId}")
    fun updateTask(@PathVariable taskId: String, @Valid @RequestBody request: TaskRequest): TaskResponse {
        val requester = accessService.requester(requireAuth())
        val dueAt = request.dueAt?.let(Instant::ofEpochMilli)
        return taskService.updateTask(
            requester, parseObjectId(taskId), request.title, request.comment, dueAt,
            request.assigneeUserIds.map(::parseObjectId),
            request.horseIds.map(::parseObjectId),
            request.recurrence?.toRecurrence(dueAt ?: throw repeatingTaskNeedsDate()),
            request.categoryTagId?.let(::parseObjectId),
        ).toTaskResponse()
    }

    @DeleteMapping("/{taskId}")
    fun deleteTask(@PathVariable taskId: String) {
        val requester = accessService.requester(requireAuth())
        taskService.deleteTask(requester, parseObjectId(taskId))
    }

    @PostMapping("/{taskId}/done")
    fun setDone(@PathVariable taskId: String, @RequestBody request: DoneRequest): TaskResponse {
        val requester = accessService.requester(requireAuth())
        return taskService.setDone(requester, parseObjectId(taskId), request.done).toTaskResponse()
    }

    @PutMapping("/{taskId}/occurrences/{occurrenceDueAt}")
    fun putOccurrence(
        @PathVariable taskId: String,
        @PathVariable occurrenceDueAt: Long,
        @Valid @RequestBody request: OccurrenceRequest,
    ): TaskOccurrenceResponse {
        val requester = accessService.requester(requireAuth())
        val change = TaskOccurrenceChange(
            cancelled = request.cancelled,
            title = request.title,
            comment = request.comment,
            dueAt = request.dueAt?.let(::epochMillisToInstant),
            horseIds = request.horseIds?.map(::parseObjectId),
            assigneeUserIds = request.assigneeUserIds?.map(::parseObjectId),
        )
        return occurrenceService.putOccurrence(requester, parseObjectId(taskId), epochMillisToInstant(occurrenceDueAt), change)
            .toTaskOccurrenceResponse()
    }

    @PostMapping("/{taskId}/occurrences/{occurrenceDueAt}/done")
    fun setOccurrenceDone(
        @PathVariable taskId: String,
        @PathVariable occurrenceDueAt: Long,
        @RequestBody request: DoneRequest,
    ): TaskOccurrenceResponse {
        val requester = accessService.requester(requireAuth())
        return occurrenceService.setDone(requester, parseObjectId(taskId), epochMillisToInstant(occurrenceDueAt), request.done)
            .toTaskOccurrenceResponse()
    }

    @GetMapping("/sync")
    fun sync(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<TaskResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return taskService.sync(requester, since, pageSize)
    }
}

@RestController
class TaskOccurrenceSyncController(
    private val accessService: AccessService,
    private val occurrenceService: TaskOccurrenceService,
) {
    @GetMapping("/taskoccurrences/sync")
    fun sync(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<TaskOccurrenceResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return occurrenceService.sync(requester, since, pageSize)
    }
}
