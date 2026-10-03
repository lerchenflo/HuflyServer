package com.lerchenflo.hufly.server.core

import org.springframework.dao.DuplicateKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Offline clients retry creates whose response got lost; the clientId turns the retry into a read. */
class IdempotentCreateTest {

    private val stored = mutableMapOf<String, String>()

    @Test
    fun `without clientId every call creates`() {
        var created = 0
        repeat(2) { idempotentCreate(null, existing = { error("no lookup") }) { created++; "new" } }
        assertEquals(2, created)
    }

    @Test
    fun `a known clientId answers the existing entity without creating`() {
        stored["c1"] = "first"

        val result = idempotentCreate("c1", existing = { stored[it] }) { error("must not create") }

        assertEquals("first", result)
    }

    @Test
    fun `losing the race on the unique index answers the winner`() {
        val result = idempotentCreate("c1", existing = { stored[it] }) {
            stored["c1"] = "winner"
            throw DuplicateKeyException("clientId")
        }

        assertEquals("winner", result)
    }

    @Test
    fun `a duplicate key that is not about the clientId is rethrown`() {
        assertFailsWith<DuplicateKeyException> {
            idempotentCreate("c1", existing = { stored[it] }) { throw DuplicateKeyException("email") }
        }
    }
}
