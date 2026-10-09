package com.lerchenflo.hufly.server.account.model

/** `GET /accounts/me`: works with or without a stable. */
data class AccountResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val mustChangePassword: Boolean,
    /** The latest PENDING or DECLINED request; null once accepted, withdrawn or never sent. */
    val joinRequest: OwnJoinRequestResponse?,
)

data class OwnJoinRequestResponse(
    val id: String,
    val stableId: String,
    val stableName: String,
    val stablePlace: String?,
    val status: JoinRequestStatus,
    /** Epoch milliseconds. */
    val createdAt: Long,
)

/** A request as the stable admin sees it. */
data class JoinRequestResponse(
    val id: String,
    val accountId: String,
    val displayName: String,
    val email: String,
    /** Epoch milliseconds. */
    val createdAt: Long,
)
