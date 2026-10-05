package com.lerchenflo.hufly.server.core.recurrence

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

enum class RecurrenceFrequency { DAILY, WEEKLY }

/**
 * A repeating event or task (EVT-10, TSK-5), expanded in [timeZone] from the series' first start. A date of the series
 * is identified by its original start; [starts] must produce exactly what the client's expansion produces.
 */
data class Recurrence(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    /** WEEKLY only; empty means the weekday of the first start. */
    val weekdays: List<DayOfWeek>,
    /** Inclusive, in [timeZone]. */
    val until: LocalDate?,
    /** Number of dates including the first. */
    val count: Int?,
    val timeZone: String,
) {
    fun starts(firstStart: Instant): Sequence<Instant> {
        val zone = ZoneId.of(timeZone)
        val anchor = LocalDateTime.ofInstant(firstStart, zone)
        val dates = candidateDates(anchor.toLocalDate())
            .takeWhile { until == null || it <= until }
            .let { if (count != null) it.take(count) else it }
        return dates.map { ZonedDateTime.of(LocalDateTime.of(it, anchor.toLocalTime()), zone).toInstant() }
    }

    fun isOccurrence(firstStart: Instant, candidate: Instant): Boolean =
        starts(firstStart).takeWhile { it <= candidate }.any { it == candidate }

    /** Position of the date [key] in the series, counting from 0. */
    fun indexOf(firstStart: Instant, key: Instant): Int = starts(firstStart).takeWhile { it < key }.count()

    private fun candidateDates(firstDate: LocalDate): Sequence<LocalDate> = when (frequency) {
        RecurrenceFrequency.DAILY -> generateSequence(0L) { it + 1 }.map { firstDate.plusDays(it * interval) }
        RecurrenceFrequency.WEEKLY -> {
            val days = weekdays.ifEmpty { listOf(firstDate.dayOfWeek) }.sorted()
            val firstMonday = firstDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            generateSequence(0L) { it + interval }
                .flatMap { week -> days.asSequence().map { firstMonday.plusDays(week * 7 + it.value - 1) } }
                .filter { it >= firstDate }
        }
    }
}

data class RecurrenceRequest(
    val frequency: String,
    val interval: Int = 1,
    val weekdays: List<String> = emptyList(),
    /** ISO local date. */
    val until: String? = null,
    val count: Int? = null,
    /** IANA zone id. */
    val timeZone: String,
) {
    fun toRecurrence(firstStart: Instant): Recurrence {
        val frequency = RecurrenceFrequency.entries.firstOrNull { it.name == frequency } ?: throw badRequest("Unknown frequency")
        if (interval !in 1..MAX_RECURRENCE_INTERVAL) throw badRequest("interval must be 1..$MAX_RECURRENCE_INTERVAL")
        val days = weekdays.map { name -> DayOfWeek.entries.firstOrNull { it.name == name } ?: throw badRequest("Unknown weekday") }
        if (days.distinct().size != days.size) throw badRequest("Duplicate weekday")
        if (frequency == RecurrenceFrequency.DAILY && days.isNotEmpty()) throw badRequest("Weekdays only for WEEKLY")
        if (until != null && count != null) throw badRequest("Either until or count")
        if (count != null && count !in 1..MAX_RECURRENCE_COUNT) throw badRequest("count must be 1..$MAX_RECURRENCE_COUNT")
        val zone = try {
            ZoneId.of(timeZone)
        } catch (e: DateTimeException) {
            throw badRequest("Unknown time zone")
        }
        val untilDate = until?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                throw badRequest("until must be an ISO date")
            }
        }
        if (untilDate != null && untilDate < LocalDateTime.ofInstant(firstStart, zone).toLocalDate()) {
            throw badRequest("until before the first date")
        }
        return Recurrence(frequency, interval, days, untilDate, count, timeZone)
    }
}

data class RecurrenceResponse(
    val frequency: String,
    val interval: Int,
    val weekdays: List<String>,
    val until: String?,
    val count: Int?,
    val timeZone: String,
)

fun Recurrence.toRecurrenceResponse() = RecurrenceResponse(
    frequency = frequency.name,
    interval = interval,
    weekdays = weekdays.map { it.name },
    until = until?.toString(),
    count = count,
    timeZone = timeZone,
)

/** 400 unless [key] is a date the series of [firstStart] produces. */
fun Recurrence?.requireOccurrence(firstStart: Instant, key: Instant) {
    if (this == null) throw badRequest("Not a series")
    if (!isOccurrence(firstStart, key)) throw badRequest("Not a date of this series")
}

private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

const val MAX_RECURRENCE_INTERVAL = 99
const val MAX_RECURRENCE_COUNT = 999
