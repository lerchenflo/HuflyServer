package com.lerchenflo.hufly.server.website

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactEmailTest {

    @Test
    fun `a missing or malformed contact email stops the start`() {
        for (bad in listOf("", "  ", "no-at-sign", "a@b", "x\"@evil.com", "<script>@x.com")) {
            assertFailsWith<IllegalStateException>(bad) { requireContactEmail(bad) }
        }
    }

    @Test
    fun `a valid contact email is kept trimmed`() {
        assertEquals("kontakt@hufly.at", requireContactEmail(" kontakt@hufly.at "))
    }
}
