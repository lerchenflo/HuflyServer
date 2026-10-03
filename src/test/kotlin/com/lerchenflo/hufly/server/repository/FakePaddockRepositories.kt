package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.paddock.model.HorseConflict
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit

class FakePaddockRepository : PaddockRepository {
    val paddocks = mutableListOf<Paddock>()

    override fun save(paddock: Paddock): Paddock {
        paddocks.requireUniqueClientId(paddock, { it.id }, { it.stableId }, { it.clientId })
        paddocks.removeIf { it.id == paddock.id }
        paddocks += paddock
        return paddock
    }

    override fun findById(id: ObjectId): Paddock? = paddocks.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): Paddock? =
        paddocks.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Paddock> =
        paddocks.filter { it.stableId == stableId && !it.deleted }
}

class FakeHorseGroupRepository : HorseGroupRepository {
    val groups = mutableListOf<HorseGroup>()

    override fun save(group: HorseGroup): HorseGroup {
        groups.requireUniqueClientId(group, { it.id }, { it.stableId }, { it.clientId })
        groups.removeIf { it.id == group.id }
        groups += group
        return group
    }

    override fun findById(id: ObjectId): HorseGroup? = groups.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): HorseGroup? =
        groups.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<HorseGroup> =
        groups.filter { it.stableId == stableId && !it.deleted }
}

class FakeHorseConflictRepository : HorseConflictRepository {
    val conflicts = mutableListOf<HorseConflict>()

    override fun save(conflict: HorseConflict): HorseConflict {
        conflicts.requireUniqueClientId(conflict, { it.id }, { it.stableId }, { it.clientId })
        conflicts.removeIf { it.id == conflict.id }
        conflicts += conflict
        return conflict
    }

    override fun findById(id: ObjectId): HorseConflict? = conflicts.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): HorseConflict? =
        conflicts.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<HorseConflict> =
        conflicts.filter { it.stableId == stableId && !it.deleted }
}

class FakePaddockAssignmentRepository : PaddockAssignmentRepository {
    val assignments = mutableListOf<PaddockAssignment>()

    override fun save(assignment: PaddockAssignment): PaddockAssignment {
        assignments.requireUniqueClientId(assignment, { it.id }, { it.stableId }, { it.clientId })
        assignments.removeIf { it.id == assignment.id }
        assignments += assignment
        return assignment
    }

    override fun findById(id: ObjectId): PaddockAssignment? = assignments.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): PaddockAssignment? =
        assignments.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<PaddockAssignment> =
        assignments.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
