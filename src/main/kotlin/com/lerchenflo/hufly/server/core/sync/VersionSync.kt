package com.lerchenflo.hufly.server.core.sync

import com.lerchenflo.hufly.server.core.badRequest

data class VersionSyncResponse<T>(
    val updatedEntries: List<T>,
    /** Soft-deleted rows and rows the requester may no longer see. */
    val deletedEntries: List<String>,
    /** Send back as `since` on the next call. */
    val newVersion: Long,
    val moreEntries: Boolean,
)

/**
 * [rows] come from a query `since < version <= safeWatermark`, ascending by version, limited to `pageSize + 1`
 * so the extra row tells whether more pages follow.
 */
fun <E : VersionSynced, R> versionSync(
    rows: List<E>,
    since: Long,
    pageSize: Int,
    visible: (E) -> Boolean = { true },
    toResponse: (E) -> R,
): VersionSyncResponse<R> {
    val page = rows.take(pageSize)
    val (live, gone) = page.partition { !it.deleted && visible(it) }
    return VersionSyncResponse(
        updatedEntries = live.map(toResponse),
        deletedEntries = gone.map { it.id.toHexString() },
        newVersion = page.maxOfOrNull { it.version } ?: since,
        moreEntries = rows.size > pageSize,
    )
}

fun requireValidVersionSyncRequest(since: Long, pageSize: Int) {
    if (since < 0 || pageSize !in 1..MAX_SYNC_PAGE_SIZE) {
        throw badRequest("Invalid since or page_size")
    }
}
