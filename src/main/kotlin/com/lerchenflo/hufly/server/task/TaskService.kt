package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
import com.lerchenflo.hufly.server.core.notification.NotificationEvent
import com.lerchenflo.hufly.server.core.notification.TaskAssigned
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.PaddockAssignmentRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.task.model.TaskResponse
import com.lerchenflo.hufly.server.task.model.TurnoutLink
import com.lerchenflo.hufly.server.task.model.toTaskResponse
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Creating, editing and deleting needs TASK_EDIT; seeing all tasks needs TASK_VIEW, otherwise only own ones. */
@Service
class TaskService(
    private val taskRepository: TaskRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val userRepository: UserRepository,
    private val horseRepository: HorseRepository,
    private val tagRepository: TagRepository,
    private val assignmentRepository: PaddockAssignmentRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    fun createTask(
        requester: User,
        title: String,
        comment: String,
        dueAt: Long?,
        assigneeUserIds: List<ObjectId>,
        horseIds: List<ObjectId>,
        clientId: String? = null,
        recurrence: Recurrence? = null,
        categoryTagId: ObjectId? = null,
        rotatesAssignees: Boolean = false,
        turnout: TurnoutLink? = null,
    ): StableTask {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        return idempotentCreate(clientId, { taskRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            requireDateIfRepeating(dueAt, recurrence)
            requireAssignees(requester, assigneeUserIds)
            requireHorses(requester, horseIds)
            requireCategory(requester, categoryTagId)
            requireTurnout(requester, turnout, recurrence)
            save(
                StableTask(
                    stableId = requester.stableId,
                    title = title,
                    comment = comment,
                    dueAt = dueAt,
                    assigneeUserIds = assigneeUserIds,
                    horseIds = horseIds,
                    recurrence = recurrence,
                    categoryTagId = categoryTagId,
                    rotatesAssignees = rotates(rotatesAssignees, recurrence, assigneeUserIds),
                    createdByUserId = requester.id,
                    doneByUserId = null,
                    doneAt = null,
                    updatedAt = clock.millis(),
                    updatedBy = requester.id,
                    clientId = clientId,
                    turnoutAssignmentId = turnout?.assignmentId,
                    turnoutKind = turnout?.kind,
                )
            ).also { announce(TaskAssigned(it.stableId, requester.id, it.id, it.assigneeUserIds.distinct(), null)) }
        }
    }

    fun updateTask(
        requester: User,
        taskId: ObjectId,
        title: String,
        comment: String,
        dueAt: Long?,
        assigneeUserIds: List<ObjectId>,
        horseIds: List<ObjectId>,
        recurrence: Recurrence? = null,
        categoryTagId: ObjectId? = null,
        rotatesAssignees: Boolean = false,
        turnout: TurnoutLink? = null,
    ): StableTask {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        val task = stableTask(requester, taskId)
        requireDateIfRepeating(dueAt, recurrence)
        requireAssignees(requester, assigneeUserIds)
        requireHorses(requester, horseIds)
        requireCategory(requester, categoryTagId)
        requireTurnout(requester, turnout, recurrence)
        val saved = save(
            task.copy(
                title = title,
                comment = comment,
                dueAt = dueAt,
                assigneeUserIds = assigneeUserIds,
                horseIds = horseIds,
                recurrence = recurrence,
                categoryTagId = categoryTagId,
                rotatesAssignees = rotates(rotatesAssignees, recurrence, assigneeUserIds),
                turnoutAssignmentId = turnout?.assignmentId,
                turnoutKind = turnout?.kind,
                updatedAt = clock.millis(),
                updatedBy = requester.id,
            )
        )
        val added = saved.assigneeUserIds.distinct() - task.assigneeUserIds.toSet()
        if (added.isNotEmpty()) announce(TaskAssigned(saved.stableId, requester.id, saved.id, added, null))
        // Visibility of dates follows the assignees: new ones must pull older dates, removed ones get them as deleted.
        if (saved.assigneeUserIds.toSet() != task.assigneeUserIds.toSet()) {
            occurrenceRepository.findByTaskIdAndDeletedFalse(task.id).forEach { saveOccurrence(it) }
        }
        return saved
    }

    fun deleteTask(requester: User, taskId: ObjectId) {
        accessService.requirePermission(requester, Permission.TASK_EDIT)
        val task = stableTask(requester, taskId)
        val now = clock.millis()
        occurrenceRepository.findByTaskIdAndDeletedFalse(task.id).forEach {
            saveOccurrence(it.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        }
        save(task.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
    }

    fun setDone(requester: User, taskId: ObjectId, done: Boolean): StableTask {
        val task = stableTask(requester, taskId)
        requireMayTick(requester, task.assigneeUserIds)
        if (task.recurrence != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates of a series are ticked one by one")
        val now = clock.millis()
        return save(
            task.copy(
                doneByUserId = if (done) requester.id else null,
                doneAt = if (done) now else null,
                updatedAt = now,
                updatedBy = requester.id,
            )
        )
    }

    /** Hands the change to the push notifications, see [NotificationEvent]. */
    internal fun announce(event: NotificationEvent) = events.publishEvent(event)

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<TaskResponse> {
        val seesAll = seesAll(requester)
        val coveredTaskIds = if (seesAll) emptySet() else coveredTaskIds(requester.id)
        val watermark = versionCounterService.safeWatermark(SyncCollection.TASKS)
        val rows = taskRepository.findVersionPage(
            requester.stableId, since, watermark, Limit.of(pageSize + 1),
        )
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { seesAll || requester.id in it.assigneeUserIds || it.id in coveredTaskIds },
            toResponse = { it.toTaskResponse() },
        )
    }

    private fun save(task: StableTask): StableTask =
        versionCounterService.withVersion(SyncCollection.TASKS) { version -> taskRepository.save(task.copy(version = version)) }

    internal fun saveOccurrence(occurrence: TaskOccurrence): TaskOccurrence =
        versionCounterService.withVersion(SyncCollection.TASK_OCCURRENCES) { version ->
            occurrenceRepository.save(occurrence.copy(version = version))
        }

    /** Assignees tick their task (or date); everyone else needs TASK_EDIT. */
    internal fun requireMayTick(requester: User, assigneeUserIds: List<ObjectId>) {
        if (requester.id !in assigneeUserIds) accessService.requirePermission(requester, Permission.TASK_EDIT)
    }

    /** Series a user sees without TASK_VIEW only because they stand in on one of its dates. */
    internal fun coveredTaskIds(userId: ObjectId): Set<ObjectId> =
        occurrenceRepository.findCoveredBy(userId).mapTo(mutableSetOf()) { it.taskId }

    /** New viewers then pull the task and its older dates, and users who lost it get them as deleted. */
    internal fun restamp(task: StableTask) {
        save(task)
        occurrenceRepository.findByTaskIdAndDeletedFalse(task.id).forEach { saveOccurrence(it) }
    }

    internal fun seesAll(requester: User) = Permission.TASK_VIEW in accessService.effectivePermissions(requester)

    internal fun requireAssignees(requester: User, assigneeUserIds: List<ObjectId>) {
        val valid = assigneeUserIds.isNotEmpty() && assigneeUserIds.all { id ->
            userRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true
        }
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown or missing assignee")
    }

    internal fun requireHorses(requester: User, horseIds: List<ObjectId>) {
        val valid = horseIds.all { id ->
            horseRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true
        }
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown horse")
    }

    private fun rotates(requested: Boolean, recurrence: Recurrence?, assigneeUserIds: List<ObjectId>) =
        requested && recurrence != null && assigneeUserIds.size >= 2

    private fun requireDateIfRepeating(dueAt: Long?, recurrence: Recurrence?) {
        if (dueAt == null && recurrence != null) throw repeatingTaskNeedsDate()
    }

    private fun requireCategory(requester: User, categoryTagId: ObjectId?) {
        if (categoryTagId == null) return
        val valid = tagRepository.findById(categoryTagId)
            ?.let { it.stableId == requester.stableId && !it.deleted && it.type == TagType.TASK_CATEGORY } == true
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown task category")
    }

    private fun requireTurnout(requester: User, turnout: TurnoutLink?, recurrence: Recurrence?) {
        if (turnout == null) return
        if (recurrence != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A turnout chore cannot repeat")
        val known = assignmentRepository.findById(turnout.assignmentId)?.let { it.stableId == requester.stableId && !it.deleted } == true
        if (!known) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown paddock assignment")
    }

    internal fun stableTask(requester: User, taskId: ObjectId): StableTask =
        taskRepository.findById(taskId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found")
}

internal fun repeatingTaskNeedsDate() = ResponseStatusException(HttpStatus.BAD_REQUEST, "A repeating task needs a date")
