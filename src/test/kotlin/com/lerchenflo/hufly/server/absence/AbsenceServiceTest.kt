package com.lerchenflo.hufly.server.absence

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.repository.FakeAbsenceRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Absences (Abwesenheiten): own ones for everyone, others' with TASK_EDIT or as admin; every member sees all. */
class AbsenceServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val absenceRepository = FakeAbsenceRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val service = AbsenceService(absenceRepository, userRepository, accessService, versionCounterService, clock)

    private val admin = testUser()
    private val plannerTag = testTag(permissions = setOf(Permission.TASK_EDIT))
    private val planner = testUser(roleTagIds = listOf(plannerTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)
    private val from = LocalDate.of(2026, 10, 12)
    private val until = LocalDate.of(2026, 10, 18)

    @BeforeTest
    fun setUp() {
        listOf(admin, planner, anna, ben, foreigner).forEach { userRepository.save(it) }
        tagRepository.save(plannerTag)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun data(user: User = anna, from: LocalDate = this.from, until: LocalDate = this.until, note: String = "Urlaub") =
        AbsenceService.AbsenceData(user.id, from, until, note)

    private fun stored(id: ObjectId) = absenceRepository.findById(id)!!

    @Test
    fun `a member enters their own absence`() {
        val absence = service.createAbsence(anna, data())

        val stored = stored(absence.id)
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals(anna.id, stored.userId)
        assertEquals(from to until, stored.from to stored.until)
        assertEquals("Urlaub", stored.note)
        assertEquals(anna.id, stored.createdByUserId)
        assertEquals(clock.instant(), stored.updatedAt)
        assertEquals(1, stored.version)
    }

    @Test
    fun `others' absences need TASK_EDIT or the admin`() {
        assertStatus(HttpStatus.FORBIDDEN) { service.createAbsence(ben, data(user = anna)) }

        service.createAbsence(planner, data(user = anna))
        service.createAbsence(admin, data(user = anna))
    }

    @Test
    fun `editing and deleting need rights on the stored and the new user`() {
        val own = service.createAbsence(anna, data())

        assertStatus(HttpStatus.FORBIDDEN) { service.updateAbsence(anna, own.id, data(user = ben)) }
        assertStatus(HttpStatus.FORBIDDEN) { service.updateAbsence(ben, own.id, data(user = ben)) }
        assertStatus(HttpStatus.FORBIDDEN) { service.deleteAbsence(ben, own.id) }

        service.updateAbsence(anna, own.id, data(note = "Krank"))
        assertEquals("Krank", stored(own.id).note)
        service.updateAbsence(planner, own.id, data(user = ben))
        assertEquals(ben.id, stored(own.id).userId)

        service.deleteAbsence(ben, own.id)
        assertTrue(stored(own.id).deleted)
        assertStatus(HttpStatus.NOT_FOUND) { service.updateAbsence(ben, own.id, data(user = ben)) }
    }

    @Test
    fun `an absence ends on or after its start and belongs to a live user of the stable`() {
        service.createAbsence(anna, data(until = from))
        assertStatus(HttpStatus.BAD_REQUEST) { service.createAbsence(anna, data(until = from.minusDays(1))) }

        val gone = userRepository.save(testUser(deleted = true))
        listOf(foreigner, gone).forEach { user ->
            assertStatus(HttpStatus.BAD_REQUEST) { service.createAbsence(admin, data(user = user)) }
        }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createAbsence(admin, AbsenceService.AbsenceData(ObjectId.get(), from, until, "")) }
    }

    @Test
    fun `absences of another stable are not found`() {
        val foreign = service.createAbsence(foreigner, data(user = foreigner))

        assertStatus(HttpStatus.NOT_FOUND) { service.updateAbsence(admin, foreign.id, data()) }
        assertStatus(HttpStatus.NOT_FOUND) { service.deleteAbsence(admin, foreign.id) }
    }

    @Test
    fun `a retried create answers the first absence`() {
        val first = service.createAbsence(anna, data(), clientId = "a1")

        assertEquals(first, service.createAbsence(anna, data(note = "anders"), clientId = "a1"))
        assertEquals(1, absenceRepository.absences.size)
    }

    @Test
    fun `every member syncs all absences of the stable, deletes as ids`() {
        val annas = service.createAbsence(anna, data())
        val bens = service.createAbsence(ben, data(user = ben))
        service.createAbsence(foreigner, data(user = foreigner))
        service.deleteAbsence(ben, bens.id)

        val page = service.sync(anna, since = 0, pageSize = 400)

        assertEquals(listOf(annas.id.toHexString()), page.updatedEntries.map { it.id })
        assertEquals(listOf(bens.id.toHexString()), page.deletedEntries)
    }

    @Test
    fun `a removed member's absences are deleted`() {
        val annas = service.createAbsence(anna, data())
        val bens = service.createAbsence(ben, data(user = ben))

        service.removeUser(anna.id, admin.id)

        assertTrue(stored(annas.id).deleted)
        assertEquals(admin.id, stored(annas.id).updatedBy)
        assertTrue(stored(annas.id).version > stored(bens.id).version)
        assertEquals(false, stored(bens.id).deleted)
    }
}
