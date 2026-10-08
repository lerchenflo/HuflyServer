package com.lerchenflo.hufly.server.task.model

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** A stable chore. Done as soon as any assignee ticks it (TSK-4). Synced by [version]. */
@Document("tasks")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class StableTask(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val title: String,
    val comment: String,
    /** Null for an undated task ("Ohne Termin"), open until ticked; never for a series. */
    val dueAt: Long?,
    val assigneeUserIds: List<ObjectId>,
    /** Optional horses the chore is about (HOR-6). */
    val horseIds: List<ObjectId>,
    /** Null for a single task; else [dueAt] is the first date (TSK-5) and dates are ticked one by one. */
    val recurrence: Recurrence? = null,
    /** A [com.lerchenflo.hufly.server.tag.model.TagType.TASK_CATEGORY] tag; may dangle after the tag is deleted. */
    val categoryTagId: ObjectId? = null,
    /** Dates of the series go to [assigneeUserIds] in turn, see [StableTask.rotationAssignee]. */
    val rotatesAssignees: Boolean = false,
    val createdByUserId: ObjectId,
    val doneByUserId: ObjectId?,
    val doneAt: Long?,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
    /** Set for a bring-out/bring-in chore of a paddock assignment, which keeps it in step, see [com.lerchenflo.hufly.server.task.TurnoutTaskService]. */
    @Indexed(sparse = true) val turnoutAssignmentId: ObjectId? = null,
    val turnoutKind: TurnoutKind? = null,
)

/** [OUT] is due at the assignment's start, [IN] at its end. */
enum class TurnoutKind { OUT, IN }

data class TurnoutLink(val assignmentId: ObjectId, val kind: TurnoutKind)

/** The assignee whose turn the series date [occurrenceDueAt] is; must match the client. */
fun StableTask.rotationAssignee(occurrenceDueAt: Long): ObjectId? {
    if (!rotatesAssignees || recurrence == null || dueAt == null || assigneeUserIds.isEmpty()) return null
    return assigneeUserIds[recurrence.indexOf(dueAt, occurrenceDueAt) % assigneeUserIds.size]
}
