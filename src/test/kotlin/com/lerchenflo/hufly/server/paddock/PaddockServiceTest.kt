package com.lerchenflo.hufly.server.paddock

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.repository.FakeHorseConflictRepository
import com.lerchenflo.hufly.server.repository.FakeHorseGroupRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakePaddockAssignmentRepository
import com.lerchenflo.hufly.server.repository.FakePaddockRepository
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
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paddock planning: paddocks, horse groups, assignments with start and optional end, overridable conflict warnings. */
class PaddockServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val paddockRepository = FakePaddockRepository()
    private val groupRepository = FakeHorseGroupRepository()
    private val conflictRepository = FakeHorseConflictRepository()
    private val assignmentRepository = FakePaddockAssignmentRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val service = PaddockService(
        paddockRepository, groupRepository, conflictRepository, assignmentRepository, horseRepository,
        accessService, versionCounterService, clock,
    )

    private val admin = testUser()
    private val plannerTag = testTag(permissions = setOf(Permission.PADDOCK_PLAN))
    private val planner = testUser(roleTagIds = listOf(plannerTag.id))
    private val rider = testUser()
    private val blitz = testHorse(name = "Blitz")
    private val donner = testHorse(name = "Donner")
    private val wolke = testHorse(name = "Wolke")
    private val foreignHorse = testHorse(stableId = OTHER_STABLE_ID)
    private val start = Instant.parse("2026-10-03T07:00:00Z")

    @BeforeTest
    fun setUp() {
        listOf(admin, planner, rider).forEach { userRepository.save(it) }
        tagRepository.save(plannerTag)
        listOf(blitz, donner, wolke, foreignHorse).forEach { horseRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, kotlin.test.assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    // Paddocks

    @Test
    fun `planner creates, edits and soft deletes a paddock`() {
        val paddock = service.createPaddock(planner, "Große Koppel", "Hinter dem Stall")
        assertEquals(STABLE_ID, paddock.stableId)

        service.updatePaddock(planner, paddock.id, "Kleine Koppel", "")
        assertEquals("Kleine Koppel", paddockRepository.findById(paddock.id)!!.name)

        service.deletePaddock(planner, paddock.id)
        assertTrue(paddockRepository.findById(paddock.id)!!.deleted)
        assertEquals(clock.instant(), paddockRepository.findById(paddock.id)!!.updatedAt)
    }

    @Test
    fun `every write needs PADDOCK_PLAN`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        val group = service.createGroup(planner, "Wallache", listOf(blitz.id))

        assertStatus(HttpStatus.FORBIDDEN) { service.createPaddock(rider, "X", "") }
        assertStatus(HttpStatus.FORBIDDEN) { service.updatePaddock(rider, paddock.id, "X", "") }
        assertStatus(HttpStatus.FORBIDDEN) { service.deletePaddock(rider, paddock.id) }
        assertStatus(HttpStatus.FORBIDDEN) { service.createGroup(rider, "X", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { service.updateGroup(rider, group.id, "X", emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { service.deleteGroup(rider, group.id) }
        assertStatus(HttpStatus.FORBIDDEN) { service.createConflict(rider, blitz.id, donner.id, "") }
        assertStatus(HttpStatus.FORBIDDEN) { service.createAssignment(rider, paddock.id, emptyList(), listOf(blitz.id), start, null, "") }
    }

    // Groups

    @Test
    fun `groups hold live horses of the own stable`() {
        val group = service.createGroup(planner, "Wallache", listOf(blitz.id, donner.id))
        assertEquals(listOf(blitz.id, donner.id), group.horseIds)

        service.updateGroup(planner, group.id, "Wallache", listOf(blitz.id))
        assertEquals(listOf(blitz.id), groupRepository.findById(group.id)!!.horseIds)

        assertStatus(HttpStatus.BAD_REQUEST) { service.createGroup(planner, "X", listOf(foreignHorse.id)) }
    }

    // Conflicts

    @Test
    fun `conflict stores a pair of different horses with a reason`() {
        val conflict = service.createConflict(planner, blitz.id, donner.id, "Beißt")

        assertEquals(setOf(blitz.id, donner.id), setOf(conflict.firstHorseId, conflict.secondHorseId))
        assertEquals("Beißt", conflict.reason)
    }

    @Test
    fun `conflict needs two different own horses and each pair only once`() {
        service.createConflict(planner, blitz.id, donner.id, "Beißt")

        assertStatus(HttpStatus.BAD_REQUEST) { service.createConflict(planner, blitz.id, blitz.id, "") }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createConflict(planner, blitz.id, foreignHorse.id, "") }
        assertStatus(HttpStatus.CONFLICT) { service.createConflict(planner, donner.id, blitz.id, "Tritt") }
    }

    @Test
    fun `conflict reason can be edited`() {
        val conflict = service.createConflict(planner, blitz.id, donner.id, "Beißt")

        service.updateConflictReason(planner, conflict.id, "Tritt")

        assertEquals("Tritt", conflictRepository.findById(conflict.id)!!.reason)
        assertStatus(HttpStatus.FORBIDDEN) { service.updateConflictReason(rider, conflict.id, "X") }
    }

    @Test
    fun `deleted conflict pair can be added again`() {
        val conflict = service.createConflict(planner, blitz.id, donner.id, "Beißt")
        service.deleteConflict(planner, conflict.id)

        service.createConflict(planner, blitz.id, donner.id, "Wieder")
    }

    // Assignments

    @Test
    fun `assignment resolves group horses plus single horses at save time`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        val group = service.createGroup(planner, "Wallache", listOf(blitz.id, donner.id))

        val assignment = service.createAssignment(planner, paddock.id, listOf(group.id), listOf(wolke.id, blitz.id), start, null, "")

        assertEquals(listOf(blitz.id, donner.id, wolke.id), assignment.horseIds)
        assertEquals(listOf(group.id), assignment.groupIds)
        assertEquals(1, assignment.version)

        service.updateGroup(planner, group.id, "Wallache", listOf(blitz.id))
        assertEquals(listOf(blitz.id, donner.id, wolke.id), assignmentRepository.findById(assignment.id)!!.horseIds)
    }

    @Test
    fun `assignment allows an open end but not an end before the start`() {
        val paddock = service.createPaddock(planner, "Koppel", "")

        service.createAssignment(planner, paddock.id, emptyList(), listOf(blitz.id), start, null, "")
        assertStatus(HttpStatus.BAD_REQUEST) {
            service.createAssignment(planner, paddock.id, emptyList(), listOf(blitz.id), start, start.minusSeconds(60), "")
        }
    }

    @Test
    fun `assignment needs own paddock, groups and horses and at least one horse`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        val foreignPaddock = paddockRepository.save(paddock.copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))

        assertStatus(HttpStatus.BAD_REQUEST) { service.createAssignment(planner, foreignPaddock.id, emptyList(), listOf(blitz.id), start, null, "") }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createAssignment(planner, paddock.id, listOf(ObjectId.get()), listOf(blitz.id), start, null, "") }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createAssignment(planner, paddock.id, emptyList(), listOf(foreignHorse.id), start, null, "") }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createAssignment(planner, paddock.id, emptyList(), emptyList(), start, null, "") }
    }

    @Test
    fun `conflicting horses can still share a paddock`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        service.createConflict(planner, blitz.id, donner.id, "Beißt")

        service.createAssignment(planner, paddock.id, emptyList(), listOf(blitz.id, donner.id), start, null, "Nur kurz")
    }

    @Test
    fun `assignment edits and deletes take new versions and sync to everyone`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        val assignment = service.createAssignment(planner, paddock.id, emptyList(), listOf(blitz.id), start, null, "")

        service.updateAssignment(planner, assignment.id, paddock.id, emptyList(), listOf(donner.id), start, start.plusSeconds(3600), "")
        assertEquals(2, assignmentRepository.findById(assignment.id)!!.version)
        service.deleteAssignment(planner, assignment.id)

        val result = service.syncAssignments(rider, since = 0, pageSize = 400)
        assertEquals(listOf(assignment.id.toHexString()), result.deletedEntries)
        assertEquals(3, result.newVersion)
    }

    @Test
    fun `foreign or deleted records are not found`() {
        val paddock = service.createPaddock(planner, "Koppel", "")
        service.deletePaddock(planner, paddock.id)
        val group = service.createGroup(planner, "G", emptyList())
        service.deleteGroup(planner, group.id)

        assertStatus(HttpStatus.NOT_FOUND) { service.updatePaddock(planner, paddock.id, "X", "") }
        assertStatus(HttpStatus.NOT_FOUND) { service.updateGroup(planner, group.id, "X", emptyList()) }
        assertStatus(HttpStatus.NOT_FOUND) { service.deleteConflict(planner, ObjectId.get()) }
        assertStatus(HttpStatus.NOT_FOUND) { service.deleteAssignment(planner, ObjectId.get()) }
    }
}
