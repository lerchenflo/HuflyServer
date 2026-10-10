package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.picture.FakePictureStore
import com.lerchenflo.hufly.server.core.picture.testPng
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.foodplan.FoodPlanService
import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.horselog.HorseLogService
import com.lerchenflo.hufly.server.paddock.PaddockService
import com.lerchenflo.hufly.server.repository.FakeFoodPlanRepository
import com.lerchenflo.hufly.server.repository.FakeHorseConflictRepository
import com.lerchenflo.hufly.server.repository.FakeHorseGroupRepository
import com.lerchenflo.hufly.server.repository.FakeHorseLogRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakePaddockAssignmentRepository
import com.lerchenflo.hufly.server.repository.FakePaddockRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.task.TurnoutTaskService
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HORSE_EDIT_OWN: owners ("Einsteller") handle their own horses, never others' (co-riders included). */
class HorseEditOwnTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val foodPlanRepository = FakeFoodPlanRepository()
    private val groupRepository = FakeHorseGroupRepository()
    private val conflictRepository = FakeHorseConflictRepository()
    private val logRepository = FakeHorseLogRepository()
    private val paddockRepository = FakePaddockRepository()
    private val assignmentRepository = FakePaddockAssignmentRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val horseService = HorseService(
        horseRepository, userRepository, foodPlanRepository, groupRepository, conflictRepository, FakePictureStore(), accessService, clock,
    )
    private val foodPlanService = FoodPlanService(foodPlanRepository, horseRepository, tagRepository, accessService, clock)
    private val logService = HorseLogService(logRepository, horseRepository, tagRepository, userRepository, accessService, versionCounterService, clock)
    private val paddockService = PaddockService(
        paddockRepository, groupRepository, conflictRepository, assignmentRepository, horseRepository, accessService, versionCounterService, clock,
        TurnoutTaskService(FakeTaskRepository(), versionCounterService, clock),
    )

    private val admin = testUser()
    private val ownTag = testTag(permissions = setOf(Permission.HORSE_EDIT_OWN))
    private val owner = testUser(roleTagIds = listOf(ownTag.id))
    private val otherOwner = testUser(roleTagIds = listOf(ownTag.id))
    private val activity = testTag(type = TagType.ACTIVITY)
    private val blitz = testHorse(name = "Blitz").copy(ownerUserId = owner.id)
    private val donner = testHorse(name = "Donner").copy(ownerUserId = owner.id)
    private val sturm = testHorse(name = "Sturm").copy(ownerUserId = otherOwner.id, coRiderUserIds = listOf(owner.id))
    private val aspirin = Medication("Aspirin", "1x täglich", epochDay(2026, 9, 1), null)

    @BeforeTest
    fun setUp() {
        listOf(admin, owner, otherOwner).forEach { userRepository.save(it) }
        listOf(ownTag, activity).forEach { tagRepository.save(it) }
        listOf(blitz, donner, sturm).forEach { horseRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun data(horse: Horse, name: String = horse.name, ownerUserId: ObjectId? = horse.ownerUserId, foodPlanId: ObjectId? = horse.foodPlanId) =
        HorseService.HorseData(
            name = name, description = "", birthDate = null, breed = "", color = "", ownerUserId = ownerUserId,
            medicalNotes = "", vetContact = "", foodPlanId = foodPlanId, coRiderUserIds = horse.coRiderUserIds,
        )

    private fun plan(createdBy: ObjectId? = null) = foodPlanRepository.save(
        FoodPlan(stableId = STABLE_ID, name = "Heu", entries = emptyList(), updatedAt = 0L, updatedBy = admin.id, createdByUserId = createdBy)
    )

    private fun logData(horse: Horse) = HorseLogService.LogData(horse.id, activity.id, clock.millis(), null, null, "", null)

    // Horses

    @Test
    fun `an owner edits their own horse, its picture and medications, never another's`() {
        assertEquals("Blitzi", horseService.updateHorse(owner, blitz.id, data(blitz, name = "Blitzi")).name)
        horseService.updateMedications(owner, blitz.id, listOf(aspirin))
        horseService.setPicture(owner, blitz.id, testPng())
        horseService.deletePicture(owner, blitz.id)

        assertStatus(HttpStatus.FORBIDDEN) { horseService.updateHorse(owner, sturm.id, data(sturm, name = "Sturmi")) }
        assertStatus(HttpStatus.FORBIDDEN) { horseService.updateMedications(owner, sturm.id, listOf(aspirin)) }
        assertStatus(HttpStatus.FORBIDDEN) { horseService.setPicture(owner, sturm.id, testPng()) }
        assertStatus(HttpStatus.FORBIDDEN) { horseService.deletePicture(owner, sturm.id) }
    }

    @Test
    fun `an owner may not hand the horse to someone else, nor create or delete horses`() {
        val error = assertFailsWith<ResponseStatusException> { horseService.updateHorse(owner, blitz.id, data(blitz, ownerUserId = otherOwner.id)) }
        assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        assertEquals("Only HORSE_EDIT may change the owner", error.reason)

        assertStatus(HttpStatus.FORBIDDEN) { horseService.createHorse(owner, data(blitz), emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { horseService.deleteHorse(owner, blitz.id) }
    }

    @Test
    fun `an owner repoints their horse to any plan of the stable`() {
        val heu = plan()

        assertEquals(heu.id, horseService.updateHorse(owner, blitz.id, data(blitz, foodPlanId = heu.id)).foodPlanId)
        assertEquals(null, foodPlanService.assignPlan(owner, blitz.id, null).foodPlanId)
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.assignPlan(owner, sturm.id, heu.id) }
    }

    @Test
    fun `owners see medications of their own horses only`() {
        val visible = horseService.medicationVisibility(owner)

        assertTrue(visible(blitz))
        assertFalse(visible(sturm))
    }

    // Horse log

    @Test
    fun `an owner writes the log of their own horses only, and of horses they co-ride`() {
        val wind = horseRepository.save(testHorse(name = "Wind").copy(ownerUserId = otherOwner.id))
        val entry = logService.createEntry(owner, logData(blitz))
        logService.updateEntry(owner, entry.id, logData(donner))
        logService.updateEntry(owner, entry.id, logData(sturm))
        logService.updateEntry(owner, entry.id, logData(blitz))

        assertStatus(HttpStatus.FORBIDDEN) { logService.updateEntry(owner, entry.id, logData(wind)) }
        assertStatus(HttpStatus.FORBIDDEN) { logService.createEntry(owner, logData(wind)) }
        val foreign = logService.createEntry(admin, logData(wind))
        assertStatus(HttpStatus.FORBIDDEN) { logService.updateEntry(owner, foreign.id, logData(blitz)) }
        assertStatus(HttpStatus.FORBIDDEN) { logService.deleteEntry(owner, foreign.id) }
        logService.deleteEntry(owner, entry.id)
    }

    // Food plans

    @Test
    fun `an owner of a horse creates plans and is stored as their creator`() {
        val created = foodPlanService.createPlan(owner, "Mein Plan", emptyList())

        assertEquals(owner.id, created.createdByUserId)
        assertEquals(owner.id, foodPlanService.copyPlan(owner, created.id, "Kopie").createdByUserId)
        val horseless = testUser(roleTagIds = listOf(ownTag.id)).also { userRepository.save(it) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.createPlan(horseless, "Plan", emptyList()) }
    }

    @Test
    fun `an owner edits plans used only by their horses, or their own unused plans`() {
        val ownPlan = plan()
        val mixed = plan()
        val mine = plan(createdBy = owner.id)
        val someoneElses = plan(createdBy = otherOwner.id)
        horseRepository.save(blitz.copy(foodPlanId = ownPlan.id))
        horseRepository.save(donner.copy(foodPlanId = mixed.id))
        horseRepository.save(sturm.copy(foodPlanId = mixed.id))

        foodPlanService.updatePlan(owner, ownPlan.id, "Neu", emptyList())
        foodPlanService.updatePlan(owner, mine.id, "Neu", emptyList())
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.updatePlan(owner, mixed.id, "Neu", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.updatePlan(owner, someoneElses.id, "Neu", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.deletePlan(owner, ownPlan.id) }
    }

    // Paddock assignments

    @Test
    fun `an owner plans, moves, ends and deletes turnouts of their own horses only`() {
        val paddock = paddockService.createPaddock(admin, "Weide", "")
        val start = clock.millis()

        val own = paddockService.createAssignment(owner, paddock.id, emptyList(), listOf(blitz.id, donner.id), start, null, "")
        paddockService.updateAssignment(owner, own.id, paddock.id, emptyList(), listOf(blitz.id), start, start.plusSeconds(3600), "")
        assertStatus(HttpStatus.FORBIDDEN) {
            paddockService.updateAssignment(owner, own.id, paddock.id, emptyList(), listOf(blitz.id, sturm.id), start, null, "")
        }
        paddockService.deleteAssignment(owner, own.id)

        assertStatus(HttpStatus.FORBIDDEN) { paddockService.createAssignment(owner, paddock.id, emptyList(), listOf(blitz.id, sturm.id), start, null, "") }
        val group = paddockService.createGroup(admin, "Wallache", listOf(blitz.id))
        assertStatus(HttpStatus.FORBIDDEN) { paddockService.createAssignment(owner, paddock.id, listOf(group.id), emptyList(), start, null, "") }
        assertStatus(HttpStatus.FORBIDDEN) { paddockService.createGroup(owner, "Meine", listOf(blitz.id)) }
        val foreign = paddockService.createAssignment(admin, paddock.id, emptyList(), listOf(sturm.id), start, null, "")
        assertStatus(HttpStatus.FORBIDDEN) {
            paddockService.updateAssignment(owner, foreign.id, paddock.id, emptyList(), listOf(blitz.id), start, null, "")
        }
        assertStatus(HttpStatus.FORBIDDEN) { paddockService.deleteAssignment(owner, foreign.id) }
    }
}
