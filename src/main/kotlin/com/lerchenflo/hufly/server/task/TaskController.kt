package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.task.model.TaskResponse
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
) {

    data class TaskRequest(
        @field:NotBlank @field:Size(max = 200) val title: String,
        @field:Size(max = 5000) val comment: String = "",
        /** Epoch milliseconds, years 1970 to 2999. */
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val dueAt: Long,
        @field:Size(max = 50) val assigneeUserIds: List<String>,
        @field:Size(max = 50) val horseIds: List<String> = emptyList(),
    )

    data class DoneRequest(val done: Boolean)

    @PostMapping
    fun createTask(@Valid @RequestBody request: TaskRequest): TaskResponse {
        val requester = accessService.requester(requireAuth())
        return taskService.createTask(
            requester, request.title, request.comment, Instant.ofEpochMilli(request.dueAt), request.assigneeUserIds.map(::parseObjectId),
            request.horseIds.map(::parseObjectId),
        ).toTaskResponse()
    }

    @PutMapping("/{taskId}")
    fun updateTask(@PathVariable taskId: String, @Valid @RequestBody request: TaskRequest): TaskResponse {
        val requester = accessService.requester(requireAuth())
        return taskService.updateTask(
            requester, parseObjectId(taskId), request.title, request.comment, Instant.ofEpochMilli(request.dueAt),
            request.assigneeUserIds.map(::parseObjectId),
            request.horseIds.map(::parseObjectId),
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

/** 3000-01-01T00:00:00Z */
private const val MAX_EPOCH_MILLIS = 32503680000000L
