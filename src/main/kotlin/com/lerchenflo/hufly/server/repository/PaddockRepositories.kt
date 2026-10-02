package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.paddock.model.HorseConflict
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface PaddockRepository : Repository<Paddock, ObjectId> {
    fun save(paddock: Paddock): Paddock
    fun findById(id: ObjectId): Paddock?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Paddock>
}

interface HorseGroupRepository : Repository<HorseGroup, ObjectId> {
    fun save(group: HorseGroup): HorseGroup
    fun findById(id: ObjectId): HorseGroup?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<HorseGroup>
}

interface HorseConflictRepository : Repository<HorseConflict, ObjectId> {
    fun save(conflict: HorseConflict): HorseConflict
    fun findById(id: ObjectId): HorseConflict?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<HorseConflict>
}

interface PaddockAssignmentRepository : Repository<PaddockAssignment, ObjectId> {
    fun save(assignment: PaddockAssignment): PaddockAssignment
    fun findById(id: ObjectId): PaddockAssignment?

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<PaddockAssignment>
}
