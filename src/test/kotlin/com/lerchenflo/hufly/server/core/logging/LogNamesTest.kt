package com.lerchenflo.hufly.server.core.logging

import ch.qos.logback.classic.spi.LoggingEvent
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every log line names its account by name in the user column, and accounts inside a message too. */
class LogNamesTest {
    private val anna = ObjectId.get()
    private val lookups = mutableListOf<ObjectId>()
    private val lookupBefore = LogNames.lookup

    @BeforeTest
    fun setUp() {
        LogNames.clearCache()
        LogNames.lookup = { id -> lookups += id; if (id == anna) "Anna Huber" else null }
    }

    @AfterTest
    fun tearDown() {
        LogNames.lookup = lookupBefore
        LogNames.clearCache()
    }

    private fun event(message: String, vararg args: Any?, user: ObjectId? = null) = LoggingEvent().apply {
        this.message = message
        argumentArray = arrayOf(*args)
        setMDCPropertyMap(user?.let { mapOf(LOG_USER to it.toHexString()) } ?: emptyMap())
    }

    private fun userColumn(user: ObjectId?) = UserConverter().convert(event("x", user = user))

    @Test
    fun `the user column shows the name of the requesting account`() {
        assertEquals("Anna Huber", userColumn(anna))
    }

    @Test
    fun `without a login the user column shows a dash`() {
        assertEquals("-", userColumn(null))
    }

    @Test
    fun `an id without an account shows the id`() {
        val gone = ObjectId.get()

        assertEquals(gone.toHexString(), userColumn(gone))
    }

    @Test
    fun `before startup, without a lookup, the id is shown`() {
        LogNames.lookup = null

        assertEquals(anna.toHexString(), userColumn(anna))
    }

    @Test
    fun `an account inside a message is printed by name`() {
        val gone = ObjectId.get()

        assertEquals(
            "Push note to Anna Huber, then to ${gone.toHexString()}",
            MentionConverter().convert(event("Push note to {}, then to {}", LogUser(anna), LogUser(gone))),
        )
    }

    @Test
    fun `a name is looked up once, then taken from the cache`() {
        userColumn(anna)
        MentionConverter().convert(event("to {}", LogUser(anna)))

        assertEquals(listOf(anna), lookups)
    }

    @Test
    fun `a failing lookup shows the id and pauses further lookups, so logging never waits on a broken database`() {
        var calls = 0
        LogNames.lookup = { calls++; error("database down") }

        assertEquals(anna.toHexString(), userColumn(anna))
        assertEquals(anna.toHexString(), userColumn(anna))
        assertEquals(1, calls)
    }
}
