package com.lerchenflo.hufly.server.foodplan

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.foodplan.model.FoodPlanEntry
import com.lerchenflo.hufly.server.foodplan.model.MealSlot
import com.lerchenflo.hufly.server.repository.FakeFoodPlanRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FOD-1..FOD-4, HOR-6 */
class FoodPlanServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val foodPlanRepository = FakeFoodPlanRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val foodPlanService = FoodPlanService(foodPlanRepository, horseRepository, tagRepository, accessService, clock)

    private val admin = testUser()
    private val feederTag = testTag(permissions = setOf(Permission.FOODPLAN_EDIT))
    private val feeder = testUser(roleTagIds = listOf(feederTag.id))
    private val rider = testUser()
    private val hay = testTag(type = TagType.FOOD)
    private val oats = testTag(type = TagType.FOOD)
    private val blitz = testHorse()

    private val morningHay = FoodPlanEntry(MealSlot.MORNING, hay.id, "2 Gabeln")
    private val nightOats = FoodPlanEntry(MealSlot.NIGHT, oats.id, "1 Schaufel")

    @BeforeTest
    fun setUp() {
        listOf(admin, feeder, rider).forEach { userRepository.save(it) }
        listOf(feederTag, hay, oats).forEach { tagRepository.save(it) }
        horseRepository.save(blitz)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored(id: ObjectId) = foodPlanRepository.findById(id)!!

    @Test
    fun `member with FOODPLAN_EDIT creates a plan with entries per meal slot`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay, nightOats))

        val stored = stored(plan.id)
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals(listOf(morningHay, nightOats), stored.entries)
        assertEquals(clock.instant(), stored.updatedAt)
        assertEquals(feeder.id, stored.updatedBy)
    }

    @Test
    fun `members without FOODPLAN_EDIT cannot write plans`() {
        val plan = foodPlanService.createPlan(admin, "Standard", listOf(morningHay))

        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.createPlan(rider, "X", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.updatePlan(rider, plan.id, "X", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.deletePlan(rider, plan.id) }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.copyPlan(rider, plan.id, "Kopie") }
        assertStatus(HttpStatus.FORBIDDEN) { foodPlanService.assignPlan(rider, blitz.id, plan.id) }
    }

    @Test
    fun `entries need live FOOD tags of the own stable`() {
        val role = tagRepository.save(testTag(type = TagType.USER_ROLE))
        val foreign = tagRepository.save(testTag(type = TagType.FOOD, stableId = OTHER_STABLE_ID))
        val deleted = tagRepository.save(testTag(type = TagType.FOOD, deleted = true))

        for (tag in listOf(role, foreign, deleted)) {
            assertStatus(HttpStatus.BAD_REQUEST) {
                foodPlanService.createPlan(feeder, "X", listOf(FoodPlanEntry(MealSlot.LUNCH, tag.id, "")))
            }
        }
    }

    @Test
    fun `edit replaces name and entries`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))
        clock.advance(Duration.ofMinutes(5))

        foodPlanService.updatePlan(feeder, plan.id, "Winter", listOf(nightOats))

        assertEquals("Winter", stored(plan.id).name)
        assertEquals(listOf(nightOats), stored(plan.id).entries)
        assertEquals(clock.instant(), stored(plan.id).updatedAt)
    }

    @Test
    fun `copy creates an independent plan for one-off changes`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))

        val copy = foodPlanService.copyPlan(feeder, plan.id, "Standard für Blitz")
        foodPlanService.updatePlan(feeder, copy.id, "Standard für Blitz", listOf(nightOats))

        assertNotEquals(plan.id, copy.id)
        assertEquals(listOf(morningHay), stored(plan.id).entries)
        assertEquals(listOf(nightOats), stored(copy.id).entries)
    }

    @Test
    fun `assigning a plan sets it on the horse and bumps the horse`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))

        foodPlanService.assignPlan(feeder, blitz.id, plan.id)

        val horse = horseRepository.findById(blitz.id)!!
        assertEquals(plan.id, horse.foodPlanId)
        assertEquals(clock.instant(), horse.updatedAt)
        assertEquals(feeder.id, horse.updatedBy)
    }

    @Test
    fun `assigning null removes the plan from the horse`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))
        foodPlanService.assignPlan(feeder, blitz.id, plan.id)

        foodPlanService.assignPlan(feeder, blitz.id, null)

        assertNull(horseRepository.findById(blitz.id)!!.foodPlanId)
    }

    @Test
    fun `several horses share one plan`() {
        val donner = horseRepository.save(testHorse())
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))

        foodPlanService.assignPlan(feeder, blitz.id, plan.id)
        foodPlanService.assignPlan(feeder, donner.id, plan.id)

        assertEquals(plan.id, horseRepository.findById(donner.id)!!.foodPlanId)
        assertEquals(plan.id, horseRepository.findById(blitz.id)!!.foodPlanId)
    }

    @Test
    fun `deleting a plan soft deletes it and clears it from its horses`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))
        foodPlanService.assignPlan(feeder, blitz.id, plan.id)
        clock.advance(Duration.ofMinutes(5))

        foodPlanService.deletePlan(feeder, plan.id)

        assertTrue(stored(plan.id).deleted)
        val horse = horseRepository.findById(blitz.id)!!
        assertNull(horse.foodPlanId)
        assertEquals(clock.instant(), horse.updatedAt)
    }

    @Test
    fun `foreign or deleted plans and horses are not found`() {
        val plan = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay))
        val foreignPlan = foodPlanRepository.save(stored(plan.id).copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))
        val foreignHorse = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val deletedHorse = horseRepository.save(testHorse(deleted = true))

        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.updatePlan(feeder, foreignPlan.id, "X", emptyList()) }
        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.copyPlan(feeder, foreignPlan.id, "X") }
        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.assignPlan(feeder, blitz.id, foreignPlan.id) }
        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.assignPlan(feeder, foreignHorse.id, plan.id) }
        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.assignPlan(feeder, deletedHorse.id, plan.id) }
        foodPlanService.deletePlan(feeder, plan.id)
        assertStatus(HttpStatus.NOT_FOUND) { foodPlanService.assignPlan(feeder, blitz.id, plan.id) }
    }

    @Test
    fun `a retried food plan create answers the first plan`() {
        val first = foodPlanService.createPlan(feeder, "Standard", listOf(morningHay), clientId = "c1")

        assertEquals(first, foodPlanService.createPlan(feeder, "Standard", listOf(morningHay), clientId = "c1"))
        assertEquals(1, foodPlanRepository.plans.size)
    }
}
