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

    @Test
    fun `operator details must be set, single-line and short`() {
        for (bad in listOf("", "   ", "line\nbreak", "x".repeat(201))) {
            assertFailsWith<IllegalStateException>(bad) { requireImprintValue("website.imprint-name", bad) }
        }
        assertEquals("Anna Muster", requireImprintValue("website.imprint-name", " Anna Muster "))
    }
}
