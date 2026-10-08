package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.testHorse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** All times are stored as Long: Long as epoch milliseconds, Long as epoch days (no timezone shift). */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoTimeStorageTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var horseRepository: HorseRepository
    @Autowired lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `instants and dates are stored as longs and read back unchanged`() {
        val birth = epochDay(2015, 4, 1)
        val updated = millis("2026-10-02T15:15:35.394Z")
        val medication = Medication("Aspirin", "1x", epochDay(2026, 9, 1), null)
        val horse = horseRepository.save(testHorse(updatedAt = updated).copy(birthDate = birth, medications = listOf(medication)))

        val raw = mongoTemplate.getCollection("horses").find(org.bson.Document("_id", horse.id)).first()!!
        assertEquals(updated, raw["updatedAt"])
        assertEquals(birth, raw["birthDate"])
        assertEquals(epochDay(2026, 9, 1), (raw["medications"] as List<*>).filterIsInstance<org.bson.Document>().single()["from"])

        val read = horseRepository.findById(horse.id)!!
        assertEquals(birth, read.birthDate)
        assertEquals(updated, read.updatedAt)
        assertEquals(listOf(medication), read.medications)
    }

    @Test
    fun `dates before 1970 are stored as negative longs`() {
        val birth = epochDay(1965, 6, 15)
        val horse = horseRepository.save(testHorse(updatedAt = millis("1969-12-31T23:59:59Z")).copy(birthDate = birth))

        val raw = mongoTemplate.getCollection("horses").find(org.bson.Document("_id", horse.id)).first()!!
        assertEquals(-1661L, raw["birthDate"])
        assertEquals(-1000L, raw["updatedAt"])
        assertEquals(birth, horseRepository.findById(horse.id)!!.birthDate)
    }
}
