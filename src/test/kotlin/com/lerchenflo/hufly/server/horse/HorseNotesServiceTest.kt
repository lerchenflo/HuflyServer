package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.horse.model.HorseNote
import com.lerchenflo.hufly.server.horse.model.HorseNoteArea.FEEDING
import com.lerchenflo.hufly.server.horse.model.HorseNoteArea.GENERAL
import com.lerchenflo.hufly.server.horse.model.HorseNoteArea.PADDOCK
import com.lerchenflo.hufly.server.horse.model.HorseNoteArea.RIDING
import com.lerchenflo.hufly.server.horse.model.PaddockRules
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakePaddockRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Horse notes per area and paddock rules (client schema 38). */
class HorseNotesServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val paddockRepository = FakePaddockRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val service = HorseNotesService(horseRepository, paddockRepository, accessService, clock)

    private val admin = testUser()
    private fun member(vararg permissions: Permission): User {
        val tag = tagRepository.save(testTag(permissions = permissions.toSet()))
        return userRepository.save(testUser(roleTagIds = listOf(tag.id)))
    }
    private val editor by lazy { member(Permission.HORSE_EDIT) }
    private val feeder by lazy { member(Permission.FOODPLAN_EDIT) }
    private val planner by lazy { member(Permission.PADDOCK_PLAN) }
    private val owner by lazy { member(Permission.HORSE_EDIT_OWN) }
    private val rider = testUser()
    private val blitz = testHorse()
    private val koppel = Paddock(stableId = STABLE_ID, name = "Koppel", description = "", updatedAt = 0, updatedBy = ObjectId.get())

    private val general = HorseNote("n1", GENERAL, "Brav beim Putzen")
    private val feeding = HorseNote("n2", FEEDING, "Heu nur nass")

    @BeforeTest
    fun setUp() {
        listOf(admin, rider).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        horseRepository.save(blitz)
        paddockRepository.save(koppel)
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored() = horseRepository.findById(blitz.id)!!

    @Test
    fun `each area needs its own right, notes of other areas must stay as they are`() {
        service.updateNotes(admin, blitz.id, listOf(general, feeding))

        service.updateNotes(feeder, blitz.id, listOf(general, feeding.copy(text = "Heu nass, kein Hafer"), HorseNote("n3", FEEDING, "Mittags Mash")))
        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(feeder, blitz.id, listOf(feeding)) }
        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(editor, blitz.id, listOf(general)) }
        service.updateNotes(editor, blitz.id, stored().notes + HorseNote("n4", RIDING, "Nur mit Gerte"))
        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(planner, blitz.id, stored().notes + HorseNote("n5", RIDING, "x")) }
        service.updateNotes(planner, blitz.id, stored().notes + HorseNote("n5", PADDOCK, "Nicht mit Donner"))
        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(rider, blitz.id, stored().notes + HorseNote("n6", GENERAL, "x")) }

        assertEquals(listOf("n1", "n2", "n3", "n4", "n5"), stored().notes.map { it.id })
        assertEquals(listOf(planner.id, clock.millis()), listOf(stored().updatedBy, stored().updatedAt))
    }

    @Test
    fun `moving a note to another area needs the rights of both`() {
        service.updateNotes(admin, blitz.id, listOf(general))

        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(editor, blitz.id, listOf(general.copy(area = FEEDING))) }
    }

    @Test
    fun `the owner writes every area of an own horse with HORSE_EDIT_OWN, not of others`() {
        horseRepository.save(blitz.copy(ownerUserId = owner.id))
        val notes = listOf(general, feeding, HorseNote("n3", PADDOCK, "Allein"), HorseNote("n4", RIDING, "Ruhig"))

        service.updateNotes(owner, blitz.id, notes)

        assertEquals(notes, stored().notes)
        val other = horseRepository.save(testHorse())
        assertStatus(HttpStatus.FORBIDDEN) { service.updateNotes(owner, other.id, listOf(general)) }
    }

    @Test
    fun `an unchanged list keeps the horse as it is`() {
        service.updateNotes(admin, blitz.id, listOf(general))
        val before = stored()
        clock.advance(days(1))

        service.updateNotes(rider, blitz.id, listOf(general))

        assertEquals(before, stored())
    }

    @Test
    fun `notes need unique ids, a text of 1 to 1000 characters and at most 50 per horse`() {
        assertStatus(HttpStatus.BAD_REQUEST) { service.updateNotes(admin, blitz.id, listOf(general, general.copy(text = "anders"))) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.updateNotes(admin, blitz.id, listOf(general.copy(text = " "))) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.updateNotes(admin, blitz.id, listOf(general.copy(text = "x".repeat(1001)))) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.updateNotes(admin, blitz.id, (1..51).map { HorseNote("n$it", GENERAL, "x") }) }
        service.updateNotes(admin, blitz.id, (1..50).map { HorseNote("n$it", GENERAL, "x".repeat(1000)) })
    }

    @Test
    fun `an unknown horse or one of another stable answers 404`() {
        assertStatus(HttpStatus.NOT_FOUND) { service.updateNotes(admin, ObjectId.get(), listOf(general)) }
        val foreign = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        assertStatus(HttpStatus.NOT_FOUND) { service.updatePaddockRules(admin, foreign.id, PaddockRules(emptyList(), true, false)) }
    }

    @Test
    fun `paddock rules need PADDOCK_PLAN and paddocks of the stable, empty rules clear them`() {
        val rules = PaddockRules(listOf(koppel.id, koppel.id), neverAloneInStable = true, neverAloneOnPaddock = false)

        service.updatePaddockRules(planner, blitz.id, rules)
        assertEquals(PaddockRules(listOf(koppel.id), true, false), stored().paddockRules)

        assertStatus(HttpStatus.FORBIDDEN) { service.updatePaddockRules(editor, blitz.id, rules) }
        val foreignPaddock = paddockRepository.save(koppel.copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))
        val gonePaddock = paddockRepository.save(koppel.copy(id = ObjectId.get(), deleted = true))
        listOf(foreignPaddock.id, gonePaddock.id, ObjectId.get()).forEach { id ->
            assertStatus(HttpStatus.BAD_REQUEST) { service.updatePaddockRules(planner, blitz.id, PaddockRules(listOf(id), false, false)) }
        }

        service.updatePaddockRules(admin, blitz.id, PaddockRules(emptyList(), false, false))
        assertNull(stored().paddockRules)
    }
}
