package com.lerchenflo.hufly.server.core.recurrence

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EVT-10, TSK-5: the expansion must match the client's exactly, since dates are keyed by their original start. */
class RecurrenceTest {

    private fun rule(
        frequency: RecurrenceFrequency = RecurrenceFrequency.WEEKLY,
        interval: Int = 1,
        weekdays: List<DayOfWeek> = emptyList(),
        until: LocalDate? = null,
        count: Int? = null,
        timeZone: String = "Europe/Vienna",
    ) = Recurrence(frequency, interval, weekdays, until, count, timeZone)

    private fun vienna(local: String) = java.time.LocalDateTime.parse(local).atZone(java.time.ZoneId.of("Europe/Vienna")).toInstant()

    @Test
    fun `daily every second day keeps the wall clock time`() {
        val first = vienna("2026-10-20T17:00")

        assertEquals(
            listOf(vienna("2026-10-20T17:00"), vienna("2026-10-22T17:00"), vienna("2026-10-24T17:00"), vienna("2026-10-26T17:00")),
            rule(RecurrenceFrequency.DAILY, interval = 2).starts(first).take(4).toList(),
        )
    }

    @Test
    fun `weekly on chosen weekdays skips dates before the first start`() {
        val wednesday = vienna("2026-10-21T17:00")

        assertEquals(
            listOf(vienna("2026-10-22T17:00"), vienna("2026-10-27T17:00"), vienna("2026-10-29T17:00")),
            rule(weekdays = listOf(DayOfWeek.THURSDAY, DayOfWeek.TUESDAY)).starts(wednesday).take(3).toList(),
        )
    }

    @Test
    fun `weekly without weekdays repeats on the first start's weekday every interval weeks`() {
        val first = vienna("2026-10-21T17:00")

        assertEquals(
            listOf(first, vienna("2026-11-04T17:00"), vienna("2026-11-18T17:00")),
            rule(interval = 2).starts(first).take(3).toList(),
        )
    }

    @Test
    fun `count and until end the series`() {
        val first = vienna("2026-10-20T17:00")

        assertEquals(3, rule(RecurrenceFrequency.DAILY, count = 3).starts(first).count())
        assertEquals(
            listOf(first, vienna("2026-10-21T17:00"), vienna("2026-10-22T17:00")),
            rule(RecurrenceFrequency.DAILY, until = LocalDate.parse("2026-10-22")).starts(first).toList(),
        )
    }

    @Test
    fun `a time inside the spring gap moves forward by the gap`() {
        val first = vienna("2027-03-27T02:30")

        assertEquals(Instant.parse("2027-03-28T01:30:00Z"), rule(RecurrenceFrequency.DAILY).starts(first).elementAt(1))
    }

    @Test
    fun `isOccurrence accepts only generated starts`() {
        val first = vienna("2026-10-20T17:00")
        val daily = rule(RecurrenceFrequency.DAILY, count = 5)

        assertTrue(daily.isOccurrence(first, vienna("2026-10-24T17:00")))
        assertFalse(daily.isOccurrence(first, vienna("2026-10-25T17:00")))
        assertFalse(daily.isOccurrence(first, vienna("2026-10-21T17:01")))
        assertFalse(daily.isOccurrence(first, vienna("2026-10-19T17:00")))
        assertFalse(rule(RecurrenceFrequency.DAILY).isOccurrence(first, Instant.ofEpochMilli(32_503_680_000_000)))
    }

    @Test
    fun `a valid request becomes a rule`() {
        val request = RecurrenceRequest("WEEKLY", 1, listOf("TUESDAY", "THURSDAY"), "2026-12-31", null, "Europe/Vienna")

        assertEquals(
            rule(weekdays = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), until = LocalDate.parse("2026-12-31")),
            request.toRecurrence(vienna("2026-10-20T17:00")),
        )
    }

    @Test
    fun `invalid requests answer 400`() {
        val first = vienna("2026-10-20T17:00")
        val invalid = listOf(
            RecurrenceRequest("MONTHLY", timeZone = "UTC"),
            RecurrenceRequest("DAILY", interval = 0, timeZone = "UTC"),
            RecurrenceRequest("DAILY", interval = 100, timeZone = "UTC"),
            RecurrenceRequest("WEEKLY", weekdays = listOf("FUNDAY"), timeZone = "UTC"),
            RecurrenceRequest("WEEKLY", weekdays = listOf("MONDAY", "MONDAY"), timeZone = "UTC"),
            RecurrenceRequest("DAILY", weekdays = listOf("MONDAY"), timeZone = "UTC"),
            RecurrenceRequest("DAILY", until = "2026-12-31", count = 3, timeZone = "UTC"),
            RecurrenceRequest("DAILY", count = 0, timeZone = "UTC"),
            RecurrenceRequest("DAILY", count = 1000, timeZone = "UTC"),
            RecurrenceRequest("DAILY", until = "31.12.2026", timeZone = "UTC"),
            RecurrenceRequest("DAILY", until = "2026-10-19", timeZone = "Europe/Vienna"),
            RecurrenceRequest("DAILY", timeZone = "Mars/Olympus"),
        )

        invalid.forEach { request ->
            val error = assertFailsWith<ResponseStatusException>(request.toString()) { request.toRecurrence(first) }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }

    @Test
    fun `responses use the client's shape`() {
        val response = rule(weekdays = listOf(DayOfWeek.TUESDAY), until = LocalDate.parse("2026-12-31")).toRecurrenceResponse()

        assertEquals(RecurrenceResponse("WEEKLY", 1, listOf("TUESDAY"), "2026-12-31", null, "Europe/Vienna"), response)
    }
}
