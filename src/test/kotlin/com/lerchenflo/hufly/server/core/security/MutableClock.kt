package com.lerchenflo.hufly.server.core.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(private var now: Instant = Instant.parse("2026-10-02T10:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}
