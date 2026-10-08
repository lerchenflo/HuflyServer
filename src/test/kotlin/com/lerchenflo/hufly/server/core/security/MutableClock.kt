package com.lerchenflo.hufly.server.core.security

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.testdata.millis

class MutableClock(private var now: Long = millis("2026-10-02T10:00:00Z")) : Clock {
    fun advance(durationMillis: Long) {
        now += durationMillis
    }

    override fun millis(): Long = now
}
