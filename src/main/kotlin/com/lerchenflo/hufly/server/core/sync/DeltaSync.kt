package com.lerchenflo.hufly.server.core.sync

import com.lerchenflo.hufly.server.core.badRequest

/** What the client already has: an entity id and its `updatedAt` in epoch milliseconds. */
data class IdTimeStamp(
    /** Not blank, at most [MAX_SYNC_ID_LENGTH] chars; checked by [requireValidSyncRequest]. */
    val id: String,
    val timeStamp: Long,
)

data class SyncResponse<T>(
    val updatedEntries: List<T>,
    /** Ids the client sent that are gone, deleted or out of reach. Only filled on page 0. */
    val deletedEntries: List<String>,
    val moreEntries: Boolean,
)

const val MAX_SYNC_PAGE_SIZE = 1000
const val MAX_SYNC_CLIENT_ENTRIES = 10_000

fun requireValidSyncRequest(page: Int, pageSize: Int, clientEntries: List<IdTimeStamp>) {
    if (page < 0 || pageSize !in 1..MAX_SYNC_PAGE_SIZE) {
        throw badRequest("Invalid page or page_size")
    }
    if (clientEntries.size > MAX_SYNC_CLIENT_ENTRIES) {
        throw badRequest("Too many sync entries")
    }
    if (clientEntries.any { it.id.isBlank() || it.id.length > MAX_SYNC_ID_LENGTH }) {
        throw badRequest("Invalid sync entry id")
    }
}

const val MAX_SYNC_ID_LENGTH = 100

/** [serverEntries] must already be limited to the requester's stable and exclude soft-deleted rows. */
fun <E : TimestampSynced, R> deltaSync(
    serverEntries: List<E>,
    clientEntries: List<IdTimeStamp>,
    page: Int,
    pageSize: Int,
    toResponse: (E) -> R,
): SyncResponse<R> {
    val clientTimestamps = clientEntries.associate { it.id to it.timeStamp }
    val changed = serverEntries
        .filter { entry ->
            val clientMillis = clientTimestamps[entry.id.toHexString()] ?: return@filter true
            entry.updatedAt > clientMillis
        }
        .sortedByDescending { it.updatedAt }

    val start = page.toLong() * pageSize
    val paged = changed.drop(start.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(pageSize)
    val serverIds = serverEntries.mapTo(mutableSetOf()) { it.id.toHexString() }
    return SyncResponse(
        updatedEntries = paged.map(toResponse),
        deletedEntries = if (page == 0) clientTimestamps.keys.filter { it !in serverIds } else emptyList(),
        moreEntries = start + pageSize < changed.size,
    )
}
