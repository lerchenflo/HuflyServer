package com.lerchenflo.hufly.server.core.mongo

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import java.time.Instant
import java.time.LocalDate

/**
 * Stores every Instant as epoch milliseconds and every LocalDate as epoch days (negative before 1970), so dates
 * never shift with the server's timezone. Mongo TTL indexes need BSON dates and therefore cannot be used.
 */
@Configuration
class MongoTimeConfig {

    @Bean
    fun mongoCustomConversions() = MongoCustomConversions(
        listOf(InstantToLong, LongToInstant, LocalDateToLong, LongToLocalDate)
    )

    @WritingConverter
    object InstantToLong : Converter<Instant, Long> {
        override fun convert(source: Instant): Long = source.toEpochMilli()
    }

    @ReadingConverter
    object LongToInstant : Converter<Long, Instant> {
        override fun convert(source: Long): Instant = Instant.ofEpochMilli(source)
    }

    @WritingConverter
    object LocalDateToLong : Converter<LocalDate, Long> {
        override fun convert(source: LocalDate): Long = source.toEpochDay()
    }

    @ReadingConverter
    object LongToLocalDate : Converter<Long, LocalDate> {
        override fun convert(source: Long): LocalDate = LocalDate.ofEpochDay(source)
    }
}
