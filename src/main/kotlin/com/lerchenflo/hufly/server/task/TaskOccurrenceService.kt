package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.notification.TaskAssigned
import com.lerchenflo.hufly.server.core.recurrence.requireOccurrence
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.event.retryOnDuplicate
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.task.model.TaskOccurrenceResponse
import com.lerchenflo.hufly.server.task.model.rotationAssignee
import com.lerchenflo.hufly.server.task.model.toTaskOccurrenceResponse
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Null fields keep the series' value; an empty [horseIds] means no horses on this date. */
data class TaskOccurrenceChange(
    val cancelled: Boolean,
    val title: String?,
    val comment: String?,
    val dueAt: Long?,
    val horseIds: List<ObjectId>?,
    /** Null means the series' assignees. */
    val assigneeUserIds: List<ObjectId>? = null,
)

/**
 * Single dates of a task series (TSK-5). One row per date carries its change (TASK_EDIT) and its tick (assignees or
 * TASK_EDIT); each write keeps the other's fields. Upserts keyed by the date's original due time.
 */
@Service
class TaskOccurrenceService(
    private val taskService: TaskService,
    private val taskRepository: TaskRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val horseRepository: HorseRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    fun putOccurrence(requester: User, taskId: ObjectId, occurrenceDueAt: Long, change: TaskOccurrenceChange): TaskOccurrence {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        val task = seriesTask(requester, taskId, occurrenceDueAt)
        validate(requester, change)
        val previousAssignees = occurrenceRepository.findByTaskIdAndOccurrenceDueAt(task.id, occurrenceDueAt)
            ?.takeUnless { it.deleted }?.assigneeUserIds.orEmpty().toSet()
        val saved = upsert(requester, task, occurrenceDueAt) {
            it.copy(
                cancelled = change.cancelled,
                title = change.title,
                comment = change.comment,
                dueAt = change.dueAt,
                horseIds = change.horseIds?.distinct(),
                assigneeUserIds = change.assigneeUserIds,
            )
        }
        // Stand-ins see the whole series, so a changed cover changes who sees it.
        if (saved.assigneeUserIds.orEmpty().toSet() != previousAssignees) taskService.restamp(task)
        val standIns = saved.assigneeUserIds.orEmpty().distinct() - previousAssignees
        if (standIns.isNotEmpty()) taskService.announce(TaskAssigned(task.stableId, requester.id, task.id, standIns, occurrenceDueAt))
        return saved
    }

    fun setDone(requester: User, taskId: ObjectId, occurrenceDueAt: Long, done: Boolean): TaskOccurrence {
        val task = seriesTask(requester, taskId, occurrenceDueAt)
        val occurrence = occurrenceRepository.findByTaskIdAndOccurrenceDueAt(task.id, occurrenceDueAt)?.takeUnless { it.deleted }
        val assignees = occurrence?.assigneeUserIds ?: task.rotationAssignee(occurrenceDueAt)?.let(::listOf) ?: task.assigneeUserIds
        taskService.requireMayTick(requester, assignees)
        if (occurrence?.cancelled == true) throw ResponseStatusException(HttpStatus.CONFLICT, "This date is cancelled")
        val now = clock.millis()
        return upsert(requester, task, occurrenceDueAt) {
            it.copy(doneByUserId = if (done) requester.id else null, doneAt = if (done) now else null)
        }
    }

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<TaskOccurrenceResponse> {
        val seesAll = taskService.seesAll(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.TASK_OCCURRENCES)
        val rows = occurrenceRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        val visibleTaskIds = if (seesAll) emptySet() else taskRepository.findByIdIn(rows.map { it.taskId }.toSet())
            .filter { requester.id in it.assigneeUserIds }.mapTo(taskService.coveredTaskIds(requester.id).toMutableSet()) { it.id }
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { seesAll || it.taskId in visibleTaskIds },
            toResponse = { it.toTaskOccurrenceResponse() },
        )
    }

    private fun seriesTask(requester: User, taskId: ObjectId, occurrenceDueAt: Long): StableTask {
        val task = taskService.stableTask(requester, taskId)
        task.recurrence.requireOccurrence(task.dueAt ?: throw badRequest("Not a series"), occurrenceDueAt)
        return task
    }

    private fun upsert(requester: User, task: StableTask, occurrenceDueAt: Long, apply: (TaskOccurrence) -> TaskOccurrence) =
        retryOnDuplicate {
            val current = occurrenceRepository.findByTaskIdAndOccurrenceDueAt(task.id, occurrenceDueAt)
                ?: TaskOccurrence(stableId = task.stableId, taskId = task.id, occurrenceDueAt = occurrenceDueAt, updatedAt = clock.millis(), updatedBy = requester.id)
            taskService.saveOccurrence(apply(current).copy(deleted = false, updatedAt = clock.millis(), updatedBy = requester.id))
        }

    private fun validate(requester: User, change: TaskOccurrenceChange) {
        change.title?.let { if (it.isBlank() || it.length > 200) throw badRequest("Title must be 1 to 200 characters") }
        change.comment?.let { if (it.length > 5000) throw badRequest("Comment too long") }
        change.dueAt?.let { if (it !in 0L..MAX_EPOCH_MILLIS) throw badRequest("Time out of range") }
        change.horseIds?.let {
            if (it.size > MAX_HORSES) throw badRequest("At most $MAX_HORSES horses")
            taskService.requireHorses(requester, it)
        }
        change.assigneeUserIds?.let {
            if (it.distinct().size != it.size) throw badRequest("Unknown or missing assignee")
            taskService.requireAssignees(requester, it)
        }
    }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}

private const val MAX_HORSES = 50
