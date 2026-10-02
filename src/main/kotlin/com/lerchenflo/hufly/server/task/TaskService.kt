package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskResponse
import com.lerchenflo.hufly.server.task.model.toTaskResponse
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

/** Creating, editing and deleting needs TASK_EDIT; seeing all tasks needs TASK_VIEW, otherwise only own ones. */
@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val userRepository: UserRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    fun createTask(requester: User, title: String, comment: String, dueAt: Instant, assigneeUserIds: List<ObjectId>): StableTask {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        requireAssignees(requester, assigneeUserIds)
        return save(
            StableTask(
                stableId = requester.stableId,
                title = title,
                comment = comment,
                dueAt = dueAt,
                assigneeUserIds = assigneeUserIds,
                createdByUserId = requester.id,
                doneByUserId = null,
                doneAt = null,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun updateTask(
        requester: User,
        taskId: ObjectId,
        title: String,
        comment: String,
        dueAt: Instant,
        assigneeUserIds: List<ObjectId>,
    ): StableTask {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        val task = stableTask(requester, taskId)
        requireAssignees(requester, assigneeUserIds)
        return save(
            task.copy(
                title = title,
                comment = comment,
                dueAt = dueAt,
                assigneeUserIds = assigneeUserIds,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteTask(requester: User, taskId: ObjectId) {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        val task = stableTask(requester, taskId)
        save(task.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun setDone(requester: User, taskId: ObjectId, done: Boolean): StableTask {
        val task = stableTask(requester, taskId)
        if (requester.id !in task.assigneeUserIds) accessService.requirePermission(requester, Permission.TASK_EDIT)
        val now = clock.instant()
        return save(
            task.copy(
                doneByUserId = if (done) requester.id else null,
                doneAt = if (done) now else null,
                updatedAt = now,
                updatedBy = requester.id,
            )
        )
    }

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<TaskResponse> {
        val seesAll = Permission.TASK_VIEW in accessService.effectivePermissions(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.TASKS)
        val rows = taskRepository.findByStableIdAndVersionGreaterThanAndVersionLessThanEqualOrderByVersionAsc(
            requester.stableId, since, watermark, Limit.of(pageSize + 1),
        )
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { seesAll || requester.id in it.assigneeUserIds },
            toResponse = { it.toTaskResponse() },
        )
    }

    private fun save(task: StableTask): StableTask =
        versionCounterService.withVersion(SyncCollection.TASKS) { version -> taskRepository.save(task.copy(version = version)) }

    private fun requireAssignees(requester: User, assigneeUserIds: List<ObjectId>) {
        val valid = assigneeUserIds.isNotEmpty() && assigneeUserIds.all { id ->
            userRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true
        }
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown or missing assignee")
    }

    private fun stableTask(requester: User, taskId: ObjectId): StableTask =
        taskRepository.findById(taskId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
}
