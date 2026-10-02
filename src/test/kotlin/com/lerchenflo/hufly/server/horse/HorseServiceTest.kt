package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** HOR-1..HOR-5, TAG-5, TAG-6 */
class HorseServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val horseService = HorseService(horseRepository, userRepository, accessService, clock)

    private val admin = testUser()
    private val editorTag = testTag(permissions = setOf(Permission.HORSE_EDIT))
    private val editor = testUser(roleTagIds = listOf(editorTag.id))
    private val rider = testUser()
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)

    private val aspirin = Medication("Aspirin", "1x täglich", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14))

    private fun data(
        name: String = "Blitz",
        ownerUserId: org.bson.types.ObjectId? = null,
        medications: List<Medication> = emptyList(),
    ) = HorseService.HorseData(
        name = name,
        description = "Brav",
        pictureUrl = null,
        birthDate = LocalDate.of(2015, 4, 1),
        breed = "Haflinger",
        color = "Fuchs",
        ownerUserId = ownerUserId,
        medicalNotes = "",
        vetContact = "Dr. Huf, 0664 123",
        medications = medications,
    )

    @BeforeTest
    fun setUp() {
        listOf(admin, editor, rider, foreigner).forEach { userRepository.save(it) }
        tagRepository.save(editorTag)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    @Test
    fun `admin adds a horse with all fields to the own stable`() {
        val horse = horseService.createHorse(admin, data(ownerUserId = rider.id, medications = listOf(aspirin)))

        val stored = horseRepository.findById(horse.id)!!
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals("Haflinger", stored.breed)
        assertEquals("Dr. Huf, 0664 123", stored.vetContact)
        assertEquals(rider.id, stored.ownerUserId)
        assertEquals(listOf(aspirin), stored.medications)
        assertEquals(clock.instant(), stored.updatedAt)
        assertEquals(admin.id, stored.updatedBy)
    }

    @Test
    fun `adding horses is admin only, even with HORSE_EDIT`() {
        assertStatus(HttpStatus.FORBIDDEN) { horseService.createHorse(editor, data()) }
    }

    @Test
    fun `owner must be a live user of the own stable`() {
        val removed = userRepository.save(testUser(deleted = true))

        for (owner in listOf(foreigner, removed)) {
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(ownerUserId = owner.id)) }
        }
    }

    @Test
    fun `medication cannot end before it starts`() {
        val backwards = aspirin.copy(until = aspirin.from.minusDays(1))

        assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(medications = listOf(backwards))) }
    }

    @Test
    fun `member with HORSE_EDIT edits a horse and keeps its food plan`() {
        val foodPlanId = org.bson.types.ObjectId.get()
        val horse = horseRepository.save(testHorse().copy(foodPlanId = foodPlanId))

        horseService.updateHorse(editor, horse.id, data(name = "Donner"))

        val stored = horseRepository.findById(horse.id)!!
        assertEquals("Donner", stored.name)
        assertEquals(foodPlanId, stored.foodPlanId)
        assertEquals(editor.id, stored.updatedBy)
        assertEquals(clock.instant(), stored.updatedAt)
    }

    @Test
    fun `admin edits without any tag`() {
        val horse = horseRepository.save(testHorse())

        horseService.updateHorse(admin, horse.id, data(name = "Donner"))

        assertEquals("Donner", horseRepository.findById(horse.id)!!.name)
    }

    @Test
    fun `member without HORSE_EDIT cannot edit`() {
        val horse = horseRepository.save(testHorse())

        assertStatus(HttpStatus.FORBIDDEN) { horseService.updateHorse(rider, horse.id, data()) }
    }

    @Test
    fun `horses of another stable or deleted horses are not found`() {
        val foreign = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val deleted = horseRepository.save(testHorse(deleted = true))

        for (horse in listOf(foreign, deleted)) {
            assertStatus(HttpStatus.NOT_FOUND) { horseService.updateHorse(admin, horse.id, data()) }
            assertStatus(HttpStatus.NOT_FOUND) { horseService.deleteHorse(admin, horse.id) }
        }
    }

    @Test
    fun `admin soft deletes a horse`() {
        val horse = horseRepository.save(testHorse())

        horseService.deleteHorse(admin, horse.id)

        val stored = horseRepository.findById(horse.id)!!
        assertTrue(stored.deleted)
        assertEquals(clock.instant(), stored.updatedAt)
    }

    @Test
    fun `removing horses is admin only, even with HORSE_EDIT`() {
        val horse = horseRepository.save(testHorse())

        assertStatus(HttpStatus.FORBIDDEN) { horseService.deleteHorse(editor, horse.id) }
    }
}
