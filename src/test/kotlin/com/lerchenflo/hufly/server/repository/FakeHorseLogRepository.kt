package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit

class FakeHorseLogRepository : HorseLogRepository {
    val entries = mutableListOf<HorseLogEntry>()

    override fun deleteByStableId(stableId: ObjectId): Long = entries.count { it.stableId == stableId }.toLong().also { entries.removeIf { it.stableId == stableId } }

    override fun save(entry: HorseLogEntry): HorseLogEntry {
        entries.requireUniqueClientId(entry, { it.id }, { it.stableId }, { it.clientId })
        entries.removeIf { it.id == entry.id }
        entries += entry
        return entry
    }

    override fun findById(id: ObjectId): HorseLogEntry? = entries.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): HorseLogEntry? =
        entries.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<HorseLogEntry> =
        entries.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
