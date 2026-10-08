package com.lerchenflo.hufly.server.testdata

import com.lerchenflo.hufly.server.core.MILLIS_PER_DAY
import com.lerchenflo.hufly.server.core.MILLIS_PER_HOUR
import com.lerchenflo.hufly.server.core.MILLIS_PER_MINUTE
import com.lerchenflo.hufly.server.core.MILLIS_PER_SECOND
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/** Epoch milliseconds of an ISO instant like "2026-10-02T10:00:00Z". */
fun millis(iso: String): Long = Instant.parse(iso).toEpochMilliseconds()

/** Epoch milliseconds of a local date-time like "2026-10-02T10:00" in [zone]. */
fun millisAt(local: String, zone: String): Long = LocalDateTime.parse(local).toInstant(TimeZone.of(zone)).toEpochMilliseconds()

/** Epoch days of an ISO date like "2026-10-02". */
fun epochDay(iso: String): Long = LocalDate.parse(iso).toEpochDays()

fun epochDay(year: Int, month: Int, day: Int): Long = LocalDate(year, month, day).toEpochDays()

fun seconds(n: Long): Long = n * MILLIS_PER_SECOND
fun minutes(n: Long): Long = n * MILLIS_PER_MINUTE
fun hours(n: Long): Long = n * MILLIS_PER_HOUR
fun days(n: Long): Long = n * MILLIS_PER_DAY

fun Long.plusSeconds(n: Long): Long = this + seconds(n)
fun Long.minusSeconds(n: Long): Long = this - seconds(n)
