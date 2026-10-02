package com.lerchenflo.hufly.server.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OFF-2, OFF-3 */
class VersionSyncTest {

    private data class Row(val id: String, val version: Long, val deleted: Boolean = false, val visible: Boolean = true)

    private fun sync(rows: List<Row>, since: Long = 0, pageSize: Int = 2) =
        versionSync(rows, since, pageSize, id = { it.id }, version = { it.version }, deleted = { it.deleted },
            visible = { it.visible }, toResponse = { it.id })

    @Test
    fun `returns one page and says more follow when the query found an extra row`() {
        val result = sync(listOf(Row("a", 3), Row("b", 4), Row("c", 5)))

        assertEquals(listOf("a", "b"), result.updatedEntries)
        assertEquals(4, result.newVersion)
        assertTrue(result.moreEntries)
    }

    @Test
    fun `last page has no more entries`() {
        val result = sync(listOf(Row("a", 3)))

        assertFalse(result.moreEntries)
        assertEquals(3, result.newVersion)
    }

    @Test
    fun `soft deleted rows come as deleted ids`() {
        val result = sync(listOf(Row("a", 3, deleted = true), Row("b", 4)))

        assertEquals(listOf("b"), result.updatedEntries)
        assertEquals(listOf("a"), result.deletedEntries)
    }

    @Test
    fun `rows the requester may not see come as deleted ids, so lost access clears them`() {
        val result = sync(listOf(Row("a", 3, visible = false), Row("b", 4)))

        assertEquals(listOf("b"), result.updatedEntries)
        assertEquals(listOf("a"), result.deletedEntries)
        assertEquals(4, result.newVersion)
    }

    @Test
    fun `empty result keeps the client's version`() {
        val result = sync(emptyList(), since = 7)

        assertEquals(7, result.newVersion)
        assertEquals(emptyList(), result.updatedEntries)
    }
}
