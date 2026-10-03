package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface HorseLogRepository : Repository<HorseLogEntry, ObjectId> {
    fun save(entry: HorseLogEntry): HorseLogEntry
    fun findById(id: ObjectId): HorseLogEntry?
    fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): HorseLogEntry?

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<HorseLogEntry>
}
