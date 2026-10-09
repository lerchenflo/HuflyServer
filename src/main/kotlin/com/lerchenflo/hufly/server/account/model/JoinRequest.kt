package com.lerchenflo.hufly.server.account.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

enum class JoinRequestStatus { PENDING, DECLINED, ACCEPTED, WITHDRAWN }

/**
 * An account asking to join a stable (sent with the stable's invite code); the admin accepts or declines it. Rows
 * stay with their final status instead of being deleted, so every change reaches the admin's app as a hint. At most
 * one PENDING request per account.
 */
@Document("joinRequests")
@CompoundIndex(name = "one_pending_per_account", def = "{ 'accountId': 1 }", unique = true, partialFilter = "{ 'status': 'PENDING' }")
data class JoinRequest(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val accountId: ObjectId,
    @Indexed val stableId: ObjectId,
    val status: JoinRequestStatus,
    val createdAt: Long,
    val updatedAt: Long,
)
