package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TurnoutKind
import org.bson.types.ObjectId
import org.springframework.stereotype.Service
import java.time.Clock

/** Keeps open bring-out/bring-in chores in step with their paddock assignment, whoever edits it. Done chores stay as they were. */
@Service
class TurnoutTaskService(
    private val taskRepository: TaskRepository,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    fun follow(assignment: PaddockAssignment, by: ObjectId) {
        if (assignment.deleted) return drop(assignment.id, by)
        openChores(assignment.id).forEach { task ->
            val dueAt = when (task.turnoutKind) {
                TurnoutKind.OUT -> assignment.startAt
                else -> assignment.endAt
            }
            val changed = if (dueAt == null) {
                task.copy(deleted = true)
            } else {
                task.copy(dueAt = dueAt, horseIds = assignment.horseIds)
            }
            if (changed != task) save(changed.copy(updatedAt = clock.instant(), updatedBy = by))
        }
    }

    fun drop(assignmentId: ObjectId, by: ObjectId) {
        openChores(assignmentId).forEach { save(it.copy(deleted = true, updatedAt = clock.instant(), updatedBy = by)) }
    }

    private fun openChores(assignmentId: ObjectId) =
        taskRepository.findByTurnoutAssignmentIdAndDeletedFalse(assignmentId).filter { it.doneAt == null }

    private fun save(task: StableTask) =
        versionCounterService.withVersion(SyncCollection.TASKS) { version -> taskRepository.save(task.copy(version = version)) }
}
