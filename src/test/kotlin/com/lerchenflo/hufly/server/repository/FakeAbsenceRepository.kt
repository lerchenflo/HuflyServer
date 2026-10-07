package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.absence.model.Absence
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit

class FakeAbsenceRepository : AbsenceRepository {
    val absences = mutableListOf<Absence>()

    override fun deleteByStableId(stableId: ObjectId): Long = absences.count { it.stableId == stableId }.toLong().also { absences.removeIf { it.stableId == stableId } }

    override fun save(absence: Absence): Absence {
        absences.requireUniqueClientId(absence, { it.id }, { it.stableId }, { it.clientId })
        absences.removeIf { it.id == absence.id }
        absences += absence
        return absence
    }

    override fun findById(id: ObjectId): Absence? = absences.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): Absence? =
        absences.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByUserIdAndDeletedFalse(userId: ObjectId): List<Absence> = absences.filter { it.userId == userId && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<Absence> =
        absences.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
