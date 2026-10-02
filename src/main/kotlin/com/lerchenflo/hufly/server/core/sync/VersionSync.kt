package com.lerchenflo.hufly.server.core.sync

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
fun <E, R> versionSync(
    rows: List<E>,
    since: Long,
    pageSize: Int,
    id: (E) -> String,
    version: (E) -> Long,
    deleted: (E) -> Boolean,
    visible: (E) -> Boolean,
    toResponse: (E) -> R,
): VersionSyncResponse<R> {
    val page = rows.take(pageSize)
    val (live, gone) = page.partition { !deleted(it) && visible(it) }
    return VersionSyncResponse(
        updatedEntries = live.map(toResponse),
        deletedEntries = gone.map(id),
        newVersion = page.maxOfOrNull(version) ?: since,
        moreEntries = rows.size > pageSize,
    )
}
