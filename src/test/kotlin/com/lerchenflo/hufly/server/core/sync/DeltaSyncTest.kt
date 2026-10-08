package com.lerchenflo.hufly.server.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** OFF-2, OFF-3 */
class DeltaSyncTest {

    private data class Item(val id: String, val updatedAt: Long)

    private fun item(id: String, millis: Long) = Item(id, millis)

    private fun sync(server: List<Item>, client: List<IdTimeStamp>, page: Int = 0, pageSize: Int = 400) =
        deltaSync(server, client, page, pageSize, id = { it.id }, updatedAt = { it.updatedAt }, toResponse = { it.id })

    @Test
    fun `returns entries the client does not know`() {
        val result = sync(listOf(item("a", 1)), emptyList())

        assertEquals(listOf("a"), result.updatedEntries)
    }

    @Test
    fun `returns only entries newer than the client timestamp`() {
        val result = sync(
            listOf(item("old", 5), item("new", 9)),
            listOf(IdTimeStamp("old", 5), IdTimeStamp("new", 8)),
        )

        assertEquals(listOf("new"), result.updatedEntries)
    }

    @Test
    fun `reports client ids the server no longer has as deleted`() {
        val result = sync(listOf(item("a", 1)), listOf(IdTimeStamp("a", 1), IdTimeStamp("gone", 1)))

        assertEquals(listOf("gone"), result.deletedEntries)
    }

    @Test
    fun `pages newest first and says when more entries follow`() {
        val server = listOf(item("a", 1), item("b", 3), item("c", 2))

        val first = sync(server, emptyList(), page = 0, pageSize = 2)
        val second = sync(server, emptyList(), page = 1, pageSize = 2)

        assertEquals(listOf("b", "c"), first.updatedEntries)
        assertTrue(first.moreEntries)
        assertEquals(listOf("a"), second.updatedEntries)
        assertFalse(second.moreEntries)
    }

    @Test
    fun `reports deletions only on the first page`() {
        val result = sync(listOf(item("a", 1)), listOf(IdTimeStamp("gone", 1)), page = 1, pageSize = 1)

        assertEquals(emptyList(), result.deletedEntries)
    }
}
