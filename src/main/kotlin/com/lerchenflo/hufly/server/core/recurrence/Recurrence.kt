package com.lerchenflo.hufly.server.core.recurrence

import com.lerchenflo.hufly.server.core.requireEpochDay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.time.Instant

enum class RecurrenceFrequency { DAILY, WEEKLY }

/** ISO weekdays in order, Monday first. */
enum class Weekday { MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY }

/**
 * A repeating event or task (EVT-10, TSK-5), expanded in [timeZone] from the series' first start. A date of the series
 * is identified by its original start in epoch milliseconds; [starts] must produce exactly what the client's expansion
 * produces.
 */
data class Recurrence(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    /** WEEKLY only; empty means the weekday of the first start. */
    val weekdays: List<Weekday>,
    /** Inclusive, in [timeZone], as epoch days. */
    val until: Long?,
    /** Number of dates including the first. */
    val count: Int?,
    val timeZone: String,
) {
    /** The series' starts in epoch milliseconds. */
    fun starts(firstStart: Long): Sequence<Long> {
        val zone = TimeZone.of(timeZone)
        val anchor = Instant.fromEpochMilliseconds(firstStart).toLocalDateTime(zone)
        val dates = candidateDates(anchor.date)
            .takeWhile { until == null || it.toEpochDays() <= until }
            .let { if (count != null) it.take(count) else it }
        return dates.map { LocalDateTime(it, anchor.time).toInstant(zone).toEpochMilliseconds() }
    }

    fun isOccurrence(firstStart: Long, candidate: Long): Boolean =
        starts(firstStart).takeWhile { it <= candidate }.any { it == candidate }

    /** Position of the date [key] in the series, counting from 0. */
    fun indexOf(firstStart: Long, key: Long): Int = starts(firstStart).takeWhile { it < key }.count()

    private fun candidateDates(firstDate: LocalDate): Sequence<LocalDate> = when (frequency) {
        RecurrenceFrequency.DAILY -> generateSequence(0L) { it + 1 }.map { firstDate.plus(it * interval, DateTimeUnit.DAY) }
        RecurrenceFrequency.WEEKLY -> {
            val firstIsoDay = firstDate.dayOfWeek.isoDayNumber
            val days = weekdays.map { it.ordinal + 1 }.ifEmpty { listOf(firstIsoDay) }.sorted()
            val firstMonday = firstDate.minus(firstIsoDay - 1, DateTimeUnit.DAY)
            generateSequence(0L) { it + interval }
                .flatMap { week -> days.asSequence().map { firstMonday.plus(week * 7 + it - 1, DateTimeUnit.DAY) } }
                .filter { it >= firstDate }
        }
    }
}

data class RecurrenceRequest(
    val frequency: String,
    val interval: Int = 1,
    val weekdays: List<String> = emptyList(),
    /** Epoch days. */
    val until: Long? = null,
    val count: Int? = null,
    /** IANA zone id. */
    val timeZone: String,
) {
    fun toRecurrence(firstStart: Long): Recurrence {
        val frequency = RecurrenceFrequency.entries.firstOrNull { it.name == frequency } ?: throw badRequest("Unknown frequency")
        if (interval !in 1..MAX_RECURRENCE_INTERVAL) throw badRequest("interval must be 1..$MAX_RECURRENCE_INTERVAL")
        val days = weekdays.map { name -> Weekday.entries.firstOrNull { it.name == name } ?: throw badRequest("Unknown weekday") }
        if (days.distinct().size != days.size) throw badRequest("Duplicate weekday")
        if (frequency == RecurrenceFrequency.DAILY && days.isNotEmpty()) throw badRequest("Weekdays only for WEEKLY")
        if (until != null && count != null) throw badRequest("Either until or count")
        if (count != null && count !in 1..MAX_RECURRENCE_COUNT) throw badRequest("count must be 1..$MAX_RECURRENCE_COUNT")
        val zone = try {
            TimeZone.of(timeZone)
        } catch (e: IllegalTimeZoneException) {
            throw badRequest("Unknown time zone")
        }
        until?.let(::requireEpochDay)
        if (until != null && until < Instant.fromEpochMilliseconds(firstStart).toLocalDateTime(zone).date.toEpochDays()) {
            throw badRequest("until before the first date")
        }
        return Recurrence(frequency, interval, days, until, count, timeZone)
    }
}

data class RecurrenceResponse(
    val frequency: String,
    val interval: Int,
    val weekdays: List<String>,
    /** Epoch days. */
    val until: Long?,
    val count: Int?,
    val timeZone: String,
)

fun Recurrence.toRecurrenceResponse() = RecurrenceResponse(
    frequency = frequency.name,
    interval = interval,
    weekdays = weekdays.map { it.name },
    until = until,
    count = count,
    timeZone = timeZone,
)

/** 400 unless [key] is a date the series of [firstStart] produces. */
fun Recurrence?.requireOccurrence(firstStart: Long, key: Long) {
    if (this == null) throw badRequest("Not a series")
    if (!isOccurrence(firstStart, key)) throw badRequest("Not a date of this series")
}

private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

const val MAX_RECURRENCE_INTERVAL = 99
const val MAX_RECURRENCE_COUNT = 999
