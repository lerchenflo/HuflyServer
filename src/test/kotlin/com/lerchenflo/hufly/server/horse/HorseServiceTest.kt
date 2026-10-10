package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.picture.FakePictureStore
import com.lerchenflo.hufly.server.core.picture.PictureKind
import com.lerchenflo.hufly.server.core.picture.testPng
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.repository.FakeFoodPlanRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.testdata.seconds
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HOR-1..HOR-5, TAG-5, TAG-6, FOD-1 */
class HorseServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val foodPlanRepository = FakeFoodPlanRepository()
    private val pictureStore = FakePictureStore()
    private val groupRepository = com.lerchenflo.hufly.server.repository.FakeHorseGroupRepository()
    private val conflictRepository = com.lerchenflo.hufly.server.repository.FakeHorseConflictRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val horseService = HorseService(horseRepository, userRepository, foodPlanRepository, groupRepository, conflictRepository, pictureStore, accessService, clock,
        com.lerchenflo.hufly.server.core.picture.PictureUploadLimiter(4, clock),
    )

    private val admin = testUser()
    private val editorTag = testTag(permissions = setOf(Permission.HORSE_EDIT))
    private val editor = testUser(roleTagIds = listOf(editorTag.id))
    private val rider = testUser()
    private val medicTag = testTag(permissions = setOf(Permission.HORSE_MEDICATION_EDIT))
    private val medic = testUser(roleTagIds = listOf(medicTag.id))
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)
    private val plannerTag = testTag(permissions = setOf(Permission.HORSE_EDIT, Permission.FOODPLAN_EDIT))
    private val planner = testUser(roleTagIds = listOf(plannerTag.id))

    private fun plan(stableId: org.bson.types.ObjectId = STABLE_ID, deleted: Boolean = false) = foodPlanRepository.save(
        FoodPlan(stableId = stableId, name = "Heu", entries = emptyList(), updatedAt = 0L, updatedBy = admin.id, deleted = deleted)
    )

    private val aspirin = Medication("Aspirin", "1x täglich", epochDay(2026, 9, 1), epochDay(2026, 9, 14))

    private fun data(
        name: String = "Blitz",
        ownerUserId: org.bson.types.ObjectId? = null,
        foodPlanId: org.bson.types.ObjectId? = null,
        coRiderUserIds: List<org.bson.types.ObjectId> = emptyList(),
    ) = HorseService.HorseData(
        name = name,
        description = "Brav",
        birthDate = epochDay(2015, 4, 1),
        breed = "Haflinger",
        color = "Fuchs",
        ownerUserId = ownerUserId,
        medicalNotes = "",
        vetContact = "Dr. Huf, 0664 123",
        foodPlanId = foodPlanId,
        coRiderUserIds = coRiderUserIds,
    )

    @BeforeTest
    fun setUp() {
        listOf(admin, editor, rider, medic, foreigner, planner).forEach { userRepository.save(it) }
        tagRepository.save(editorTag)
        tagRepository.save(plannerTag)
        tagRepository.save(medicTag)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    @Test
    fun `admin adds a horse with all fields to the own stable`() {
        val horse = horseService.createHorse(admin, data(ownerUserId = rider.id), listOf(aspirin))

        val stored = horseRepository.findById(horse.id)!!
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals("Haflinger", stored.breed)
        assertEquals("Dr. Huf, 0664 123", stored.vetContact)
        assertEquals(rider.id, stored.ownerUserId)
        assertEquals(listOf(aspirin), stored.medications)
        assertEquals(clock.millis(), stored.updatedAt)
        assertEquals(admin.id, stored.updatedBy)
    }

    @Test
    fun `adding horses is admin only, even with HORSE_EDIT`() {
        assertStatus(HttpStatus.FORBIDDEN) { horseService.createHorse(editor, data(), emptyList()) }
    }

    @Test
    fun `owner must be a live user of the own stable`() {
        val removed = userRepository.save(testUser(deleted = true))

        for (owner in listOf(foreigner, removed)) {
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(ownerUserId = owner.id), emptyList()) }
        }
    }

    @Test
    fun `medication cannot end before it starts`() {
        val backwards = aspirin.copy(until = aspirin.from - 1)

        assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(), listOf(backwards)) }
    }

    @Test
    fun `member with HORSE_EDIT edits a horse and keeps its food plan`() {
        val foodPlanId = org.bson.types.ObjectId.get()
        val horse = horseRepository.save(testHorse().copy(foodPlanId = foodPlanId))

        horseService.updateHorse(editor, horse.id, data(name = "Donner", foodPlanId = plan(deleted = true).id))

        val stored = horseRepository.findById(horse.id)!!
        assertEquals("Donner", stored.name)
        assertEquals(foodPlanId, stored.foodPlanId)
        assertEquals(editor.id, stored.updatedBy)
        assertEquals(clock.millis(), stored.updatedAt)
    }

    @Test
    fun `admin adds a horse with a food plan`() {
        val heu = plan()

        val horse = horseService.createHorse(admin, data(foodPlanId = heu.id), emptyList())

        assertEquals(heu.id, horseRepository.findById(horse.id)!!.foodPlanId)
    }

    @Test
    fun `member with HORSE_EDIT and FOODPLAN_EDIT links and unlinks a food plan`() {
        val horse = horseRepository.save(testHorse())
        val heu = plan()

        horseService.updateHorse(planner, horse.id, data(foodPlanId = heu.id))
        assertEquals(heu.id, horseRepository.findById(horse.id)!!.foodPlanId)

        horseService.updateHorse(planner, horse.id, data(foodPlanId = null))
        assertNull(horseRepository.findById(horse.id)!!.foodPlanId)
    }

    @Test
    fun `food plan must be a live plan of the own stable`() {
        val horse = horseRepository.save(testHorse())

        for (bad in listOf(plan(stableId = OTHER_STABLE_ID), plan(deleted = true))) {
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.updateHorse(planner, horse.id, data(foodPlanId = bad.id)) }
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(foodPlanId = bad.id), emptyList()) }
        }
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
        assertEquals(clock.millis(), stored.updatedAt)
    }

    @Test
    fun `removing horses is admin only, even with HORSE_EDIT`() {
        val horse = horseRepository.save(testHorse())

        assertStatus(HttpStatus.FORBIDDEN) { horseService.deleteHorse(editor, horse.id) }
    }

    @Test
    fun `horse edits never touch medications`() {
        val horse = horseRepository.save(testHorse().copy(medications = listOf(aspirin)))

        horseService.updateHorse(editor, horse.id, data(name = "Donner"))

        assertEquals(listOf(aspirin), horseRepository.findById(horse.id)!!.medications)
    }

    @Test
    fun `member with HORSE_MEDICATION_EDIT replaces medications without HORSE_EDIT`() {
        val horse = horseRepository.save(testHorse())

        horseService.updateMedications(medic, horse.id, listOf(aspirin))

        val stored = horseRepository.findById(horse.id)!!
        assertEquals(listOf(aspirin), stored.medications)
        assertEquals(medic.id, stored.updatedBy)
        assertEquals(clock.millis(), stored.updatedAt)
    }

    @Test
    fun `HORSE_EDIT alone cannot change medications`() {
        val horse = horseRepository.save(testHorse())

        assertStatus(HttpStatus.FORBIDDEN) { horseService.updateMedications(editor, horse.id, listOf(aspirin)) }
    }

    @Test
    fun `medication update checks dates and the stable`() {
        val horse = horseRepository.save(testHorse())
        val foreign = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))

        assertStatus(HttpStatus.BAD_REQUEST) {
            horseService.updateMedications(medic, horse.id, listOf(aspirin.copy(until = aspirin.from - 1)))
        }
        assertStatus(HttpStatus.NOT_FOUND) { horseService.updateMedications(medic, foreign.id, listOf(aspirin)) }
    }

    // Pictures (HOR-2, HOR-3)

    @Test
    fun `member with HORSE_EDIT uploads a picture and the url changes with every upload`() {
        val horse = horseRepository.save(testHorse())

        val first = horseService.setPicture(editor, horse.id, testPng())
        clock.advance(seconds(1))
        val second = horseService.setPicture(editor, horse.id, testPng())

        assertEquals("/horses/${horse.id.toHexString()}/picture?v=${first.updatedAt}", first.pictureUrl)
        assertTrue(first.pictureUrl != second.pictureUrl)
        assertEquals(second, horseRepository.findById(horse.id))
        assertTrue(pictureStore.load(PictureKind.HORSE, horse.id)!!.isNotEmpty())
    }

    @Test
    fun `uploading a picture needs HORSE_EDIT and a live horse of the own stable`() {
        val horse = horseRepository.save(testHorse())
        val foreign = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val removed = horseRepository.save(testHorse(deleted = true))

        assertStatus(HttpStatus.FORBIDDEN) { horseService.setPicture(rider, horse.id, testPng()) }
        assertStatus(HttpStatus.NOT_FOUND) { horseService.setPicture(admin, foreign.id, testPng()) }
        assertStatus(HttpStatus.NOT_FOUND) { horseService.setPicture(admin, removed.id, testPng()) }
        assertTrue(pictureStore.pictures.isEmpty())
    }

    @Test
    fun `a fifth picture upload within a minute answers 429, broken uploads count too`() {
        val horse = horseRepository.save(testHorse())
        repeat(2) { assertStatus(HttpStatus.BAD_REQUEST) { horseService.setPicture(editor, horse.id, byteArrayOf(1)) } }
        repeat(2) { horseService.setPicture(editor, horse.id, testPng()) }

        assertStatus(HttpStatus.TOO_MANY_REQUESTS) { horseService.setPicture(editor, horse.id, testPng()) }
        horseService.setPicture(admin, horse.id, testPng())

        clock.advance(minutes(1))
        horseService.setPicture(editor, horse.id, testPng())
    }

    @Test
    fun `refused picture uploads without permission do not count`() {
        val horse = horseRepository.save(testHorse())
        repeat(5) { assertStatus(HttpStatus.FORBIDDEN) { horseService.setPicture(rider, horse.id, testPng()) } }
        val riderTag = tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT)))
        val promoted = userRepository.save(rider.copy(roleTagIds = listOf(riderTag.id)))

        horseService.setPicture(promoted, horse.id, testPng())
    }

    @Test
    fun `removing the picture clears url and file`() {
        val horse = horseRepository.save(testHorse())
        horseService.setPicture(editor, horse.id, testPng())

        val updated = horseService.deletePicture(editor, horse.id)

        assertNull(updated.pictureUrl)
        assertNull(pictureStore.load(PictureKind.HORSE, horse.id))
        assertStatus(HttpStatus.FORBIDDEN) { horseService.deletePicture(rider, horse.id) }
    }

    @Test
    fun `every member of the stable loads the picture, nobody else`() {
        val horse = horseRepository.save(testHorse())
        horseService.setPicture(admin, horse.id, testPng())

        assertTrue(horseService.picture(rider, horse.id).isNotEmpty())
        assertStatus(HttpStatus.NOT_FOUND) { horseService.picture(foreigner, horse.id) }
        assertStatus(HttpStatus.NOT_FOUND) { horseService.picture(rider, horseRepository.save(testHorse()).id) }
    }

    @Test
    fun `horse edits keep the picture and deleting the horse removes it`() {
        val horse = horseRepository.save(testHorse())
        val url = horseService.setPicture(admin, horse.id, testPng()).pictureUrl

        horseService.updateHorse(editor, horse.id, data(name = "Donner"))
        assertEquals(url, horseRepository.findById(horse.id)!!.pictureUrl)

        horseService.deleteHorse(admin, horse.id)
        assertNull(pictureStore.load(PictureKind.HORSE, horse.id))
    }

    @Test
    fun `a retried horse create answers the first horse`() {
        val first = horseService.createHorse(admin, data(), emptyList(), clientId = "c1")

        assertEquals(first, horseService.createHorse(admin, data(), emptyList(), clientId = "c1"))
        assertEquals(1, horseRepository.horses.size)
    }

    @Test
    fun `deleting a horse removes it from groups and drops its conflicts`() {
        val horse = horseRepository.save(testHorse())
        val other = horseRepository.save(testHorse())
        val group = groupRepository.save(
            com.lerchenflo.hufly.server.paddock.model.HorseGroup(
                stableId = STABLE_ID, name = "G", horseIds = listOf(horse.id, other.id), updatedAt = 0L, updatedBy = admin.id,
            )
        )
        val untouchedGroup = groupRepository.save(group.copy(id = org.bson.types.ObjectId.get(), horseIds = listOf(other.id)))
        val conflict = conflictRepository.save(
            com.lerchenflo.hufly.server.paddock.model.HorseConflict(
                stableId = STABLE_ID, firstHorseId = other.id, secondHorseId = horse.id, reason = "", updatedAt = 0L, updatedBy = admin.id,
            )
        )

        horseService.deleteHorse(admin, horse.id)

        val storedGroup = groupRepository.findById(group.id)!!
        assertEquals(listOf(other.id), storedGroup.horseIds)
        assertEquals(clock.millis(), storedGroup.updatedAt)
        assertEquals(admin.id, storedGroup.updatedBy)
        assertEquals(untouchedGroup, groupRepository.findById(untouchedGroup.id))
        val storedConflict = conflictRepository.findById(conflict.id)!!
        assertTrue(storedConflict.deleted)
        assertEquals(clock.millis(), storedConflict.updatedAt)
    }

    // Co-riders (Reitbeteiligungen)

    @Test
    fun `a horse keeps its co-riders without duplicates or the owner`() {
        val horse = horseService.createHorse(admin, data(ownerUserId = rider.id, coRiderUserIds = listOf(editor.id, rider.id, editor.id)), emptyList())
        assertEquals(listOf(editor.id), horseRepository.findById(horse.id)!!.coRiderUserIds)

        horseService.updateHorse(editor, horse.id, data(ownerUserId = rider.id, coRiderUserIds = listOf(medic.id, planner.id)))
        assertEquals(listOf(medic.id, planner.id), horseRepository.findById(horse.id)!!.coRiderUserIds)

        horseService.updateHorse(editor, horse.id, data(ownerUserId = rider.id))
        assertEquals(emptyList(), horseRepository.findById(horse.id)!!.coRiderUserIds)
    }

    @Test
    fun `co-riders must be live users of the own stable`() {
        val gone = userRepository.save(testUser(deleted = true))
        val horse = horseService.createHorse(admin, data(), emptyList())

        listOf(foreigner.id, gone.id, org.bson.types.ObjectId.get()).forEach { id ->
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.createHorse(admin, data(coRiderUserIds = listOf(id)), emptyList()) }
            assertStatus(HttpStatus.BAD_REQUEST) { horseService.updateHorse(editor, horse.id, data(coRiderUserIds = listOf(id))) }
        }
    }
}
