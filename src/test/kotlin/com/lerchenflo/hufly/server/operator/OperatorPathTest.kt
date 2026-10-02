package com.lerchenflo.hufly.server.operator

import com.lerchenflo.hufly.server.core.security.requireValidOperatorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperatorPathTest {

    @Test
    fun `accepts a single unguessable segment`() {
        assertEquals("/hb-7f3k2q", requireValidOperatorPath("/hb-7f3k2q"))
    }

    @Test
    fun `rejects empty, root, nested, wildcard and too short paths`() {
        for (path in listOf("", "/", "hb-7f3k2q", "/a/b", "/**", "/ab", "/with space")) {
            assertFailsWith<IllegalStateException>(path) { requireValidOperatorPath(path) }
        }
    }
}
