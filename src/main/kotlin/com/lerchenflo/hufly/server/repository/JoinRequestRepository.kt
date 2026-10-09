package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository

interface JoinRequestRepository : Repository<JoinRequest, ObjectId> {
    fun save(request: JoinRequest): JoinRequest
    /** Fails with a duplicate key error if the account has a PENDING request already. */
    fun insert(request: JoinRequest): JoinRequest
    fun findById(id: ObjectId): JoinRequest?
    fun findFirstByAccountIdOrderByCreatedAtDesc(accountId: ObjectId): JoinRequest?
    fun findByAccountIdAndStatus(accountId: ObjectId, status: JoinRequestStatus): List<JoinRequest>
    fun findByAccountIdAndStableId(accountId: ObjectId, stableId: ObjectId): List<JoinRequest>
    fun findByStableIdAndStatus(stableId: ObjectId, status: JoinRequestStatus): List<JoinRequest>
    fun deleteByStableId(stableId: ObjectId): Long
    fun deleteByAccountId(accountId: ObjectId): Long

    /** Atomic: answers 1 only for the one caller that moves the PENDING request of [stableId] to [status]. */
    @Query("{ '_id': ?0, 'stableId': ?1, 'status': 'PENDING' }")
    @Update("{ '\$set': { 'status': ?2, 'updatedAt': ?3 } }")
    fun resolvePending(id: ObjectId, stableId: ObjectId, status: JoinRequestStatus, updatedAt: Long): Long
}
