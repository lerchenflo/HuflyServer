package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

class FakeJoinRequestRepository : JoinRequestRepository {
    val requests = mutableListOf<JoinRequest>()

    override fun save(request: JoinRequest): JoinRequest {
        requests.removeIf { it.id == request.id }
        requests += request
        return request
    }

    override fun insert(request: JoinRequest): JoinRequest {
        val pendingTaken = request.status == JoinRequestStatus.PENDING &&
            requests.any { it.accountId == request.accountId && it.status == JoinRequestStatus.PENDING }
        if (pendingTaken || requests.any { it.id == request.id }) throw DuplicateKeyException("accountId")
        return save(request)
    }

    override fun findById(id: ObjectId): JoinRequest? = requests.firstOrNull { it.id == id }

    override fun findFirstByAccountIdOrderByCreatedAtDesc(accountId: ObjectId): JoinRequest? =
        requests.filter { it.accountId == accountId }.maxByOrNull { it.createdAt }

    override fun findByAccountIdAndStatus(accountId: ObjectId, status: JoinRequestStatus): List<JoinRequest> =
        requests.filter { it.accountId == accountId && it.status == status }

    override fun findByAccountIdAndStableId(accountId: ObjectId, stableId: ObjectId): List<JoinRequest> =
        requests.filter { it.accountId == accountId && it.stableId == stableId }

    override fun findByStableIdAndStatus(stableId: ObjectId, status: JoinRequestStatus): List<JoinRequest> =
        requests.filter { it.stableId == stableId && it.status == status }

    override fun deleteByStableId(stableId: ObjectId): Long =
        requests.count { it.stableId == stableId }.toLong().also { requests.removeIf { it.stableId == stableId } }

    override fun deleteByAccountId(accountId: ObjectId): Long =
        requests.count { it.accountId == accountId }.toLong().also { requests.removeIf { it.accountId == accountId } }

    override fun resolvePending(id: ObjectId, stableId: ObjectId, status: JoinRequestStatus, updatedAt: Long): Long {
        val current = requests.firstOrNull { it.id == id && it.stableId == stableId && it.status == JoinRequestStatus.PENDING } ?: return 0
        save(current.copy(status = status, updatedAt = updatedAt))
        return 1
    }
}
