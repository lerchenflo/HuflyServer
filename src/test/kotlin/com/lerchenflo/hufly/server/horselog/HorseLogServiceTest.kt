package com.lerchenflo.hufly.server.horselog

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.repository.FakeHorseLogRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.minusSeconds
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.testdata.plusSeconds
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** HOR-5 (vaccinations as log entries), TAG-1 (ACTIVITY tags) */
class HorseLogServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val logRepository = FakeHorseLogRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val logService = HorseLogService(logRepository, horseRepository, tagRepository, userRepository, accessService, versionCounterService, clock)

    private val admin = testUser()
    private val writerTag = testTag(permissions = setOf(Permission.HORSE_LOG_WRITE))
    private val writer = testUser(roleTagIds = listOf(writerTag.id))
    private val rider = testUser()
    private val vaccination = testTag(type = TagType.ACTIVITY)
    private val blitz = testHorse()
    private val start = millis("2026-10-01T08:00:00Z")

    private fun data(
        horseId: ObjectId = blitz.id,
        activityTagId: ObjectId = vaccination.id,
        endAt: Long? = start.plusSeconds(1800),
        doneByUserId: ObjectId? = rider.id,
        nextDueAt: Long? = epochDay(2027, 10, 1),
    ) = HorseLogService.LogData(horseId, activityTagId, start, endAt, doneByUserId, "Influenza", nextDueAt)

    @BeforeTest
    fun setUp() {
        listOf(admin, writer, rider).forEach { userRepository.save(it) }
        listOf(writerTag, vaccination).forEach { tagRepository.save(it) }
        horseRepository.save(blitz)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored(id: ObjectId) = logRepository.findById(id)!!

    @Test
    fun `member with HORSE_LOG_WRITE logs a vaccination with next due date`() {
        val entry = logService.createEntry(writer, data())

        val stored = stored(entry.id)
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals(blitz.id, stored.horseId)
        assertEquals(vaccination.id, stored.activityTagId)
        assertEquals(rider.id, stored.doneByUserId)
        assertEquals(epochDay(2027, 10, 1), stored.nextDueAt)
        assertEquals(clock.millis(), stored.createdAt)
        assertEquals(writer.id, stored.updatedBy)
        assertEquals(1, stored.version)
    }

    @Test
    fun `members without HORSE_LOG_WRITE cannot write`() {
        val entry = logService.createEntry(writer, data())

        assertStatus(HttpStatus.FORBIDDEN) { logService.createEntry(rider, data()) }
        assertStatus(HttpStatus.FORBIDDEN) { logService.updateEntry(rider, entry.id, data()) }
        assertStatus(HttpStatus.FORBIDDEN) { logService.deleteEntry(rider, entry.id) }
    }

    @Test
    fun `horse, activity tag and done-by user must belong to the own stable`() {
        val foreignHorse = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val deletedHorse = horseRepository.save(testHorse(deleted = true))
        val foodTag = tagRepository.save(testTag(type = TagType.FOOD))
        val foreignUser = userRepository.save(testUser(stableId = OTHER_STABLE_ID))

        assertStatus(HttpStatus.BAD_REQUEST) { logService.createEntry(writer, data(horseId = foreignHorse.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { logService.createEntry(writer, data(horseId = deletedHorse.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { logService.createEntry(writer, data(activityTagId = foodTag.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { logService.createEntry(writer, data(doneByUserId = foreignUser.id)) }
    }

    @Test
    fun `end cannot be before start, open end and unknown doer are fine`() {
        assertStatus(HttpStatus.BAD_REQUEST) { logService.createEntry(writer, data(endAt = start.minusSeconds(1))) }

        logService.createEntry(writer, data(endAt = null, doneByUserId = null, nextDueAt = null))
    }

    @Test
    fun `edit replaces the fields, keeps createdAt and takes a new version`() {
        val entry = logService.createEntry(writer, data())
        clock.advance(minutes(5))

        logService.updateEntry(writer, entry.id, data(nextDueAt = null))

        val stored = stored(entry.id)
        assertEquals(null, stored.nextDueAt)
        assertEquals(entry.createdAt, stored.createdAt)
        assertEquals(clock.millis(), stored.updatedAt)
        assertEquals(2, stored.version)
    }

    @Test
    fun `delete soft deletes with a new version`() {
        val entry = logService.createEntry(writer, data())

        logService.deleteEntry(writer, entry.id)

        assertTrue(stored(entry.id).deleted)
        assertEquals(2, stored(entry.id).version)
    }

    @Test
    fun `foreign or deleted entries are not found`() {
        val entry = logService.createEntry(writer, data())
        val foreign = logRepository.save(stored(entry.id).copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))
        logService.deleteEntry(writer, entry.id)

        for (id in listOf(entry.id, foreign.id)) {
            assertStatus(HttpStatus.NOT_FOUND) { logService.updateEntry(writer, id, data()) }
            assertStatus(HttpStatus.NOT_FOUND) { logService.deleteEntry(writer, id) }
        }
    }

    @Test
    fun `every member syncs the log, deletions come as ids`() {
        val kept = logService.createEntry(writer, data())
        val removed = logService.createEntry(writer, data())
        logService.deleteEntry(writer, removed.id)

        val result = logService.sync(rider, since = 0, pageSize = 400)

        assertEquals(listOf(kept.id.toHexString()), result.updatedEntries.map { it.id })
        assertEquals(listOf(removed.id.toHexString()), result.deletedEntries)
        assertEquals(3, result.newVersion)
    }

    // Idempotent creates (OFF-2)

    @Test
    fun `a retried create with the same clientId answers the first entry and saves nothing`() {
        val first = logService.createEntry(writer, data(), clientId = "c1")

        val retry = logService.createEntry(writer, data(), clientId = "c1")

        assertEquals(first, retry)
        assertEquals(1, logRepository.entries.size)
    }

    @Test
    fun `a retry answers the entry even after it was deleted or its horse is gone`() {
        val first = logService.createEntry(writer, data(), clientId = "c1")
        logService.deleteEntry(writer, first.id)
        horseRepository.save(blitz.copy(deleted = true))

        val retry = logService.createEntry(writer, data(), clientId = "c1")

        assertEquals(first.id, retry.id)
        assertTrue(retry.deleted)
    }

    @Test
    fun `the permission check comes before the clientId lookup`() {
        logService.createEntry(writer, data(), clientId = "c1")

        assertStatus(HttpStatus.FORBIDDEN) { logService.createEntry(rider, data(), clientId = "c1") }
    }

    @Test
    fun `clientIds are scoped per stable`() {
        val foreign = logService.createEntry(writer, data(), clientId = "c1").copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID, clientId = "x")
        logRepository.save(foreign.copy(clientId = "c2"))

        val own = logService.createEntry(writer, data(), clientId = "c2")

        assertEquals(STABLE_ID, own.stableId)
        assertEquals(3, logRepository.entries.size)
    }
}
