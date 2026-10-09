package com.lerchenflo.hufly.server.stable.model

data class StableResponse(
    val id: String,
    val name: String,
    val place: String?,
    val subscriptionStatus: SubscriptionStatus,
    /** Epoch milliseconds. */
    val subscriptionValidUntil: Long?,
)

fun Stable.toStableResponse() = StableResponse(
    id = id.toHexString(),
    name = name,
    place = place,
    subscriptionStatus = subscriptionStatus,
    subscriptionValidUntil = subscriptionValidUntil,
)
