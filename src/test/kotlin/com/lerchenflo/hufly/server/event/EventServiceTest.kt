package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
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
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** EVT-1..EVT-8 */
class EventServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val eventRepository = FakeEventRepository()
    private val invitationRepository = FakeEventInvitationRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val service = EventService(
        eventRepository, invitationRepository, userRepository, horseRepository, accessService, versionCounterService, clock,
    )

    private val admin = testUser()
    private val teacherTag = testTag(permissions = setOf(Permission.EVENT_EDIT))
    private val teacher = testUser(roleTagIds = listOf(teacherTag.id))
    private val otherTeacher = testUser(roleTagIds = listOf(teacherTag.id))
    private val viewerTag = testTag(permissions = setOf(Permission.EVENT_VIEW))
    private val viewer = testUser(roleTagIds = listOf(viewerTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val clara = testUser()
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)
    private val blitz = testHorse()
    private val start = Instant.parse("2026-10-10T16:00:00Z")
    private val end = start.plusSeconds(3600)

    @BeforeTest
    fun setUp() {
        listOf(admin, teacher, otherTeacher, viewer, anna, ben, clara, foreigner).forEach { userRepository.save(it) }
        listOf(teacherTag, viewerTag).forEach { tagRepository.save(it) }
        horseRepository.save(blitz)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun lesson(by: User = teacher, invitees: List<ObjectId> = listOf(anna.id, ben.id), horses: List<ObjectId> = listOf(blitz.id)) =
        service.createEvent(by, "Reitstunde", "Halle", start, end, horses, invitees)

    private fun invitationOf(event: Event, user: User) =
        invitationRepository.findByEventIdAndDeletedFalse(event.id).single { it.userId == user.id }

    // Create

    @Test
    fun `teacher creates a lesson with horses and pending invitations`() {
        val event = lesson()

        assertEquals(STABLE_ID, event.stableId)
        assertEquals(teacher.id, event.creatorUserId)
        assertEquals(listOf(blitz.id), event.horseIds)
        val invitations = invitationRepository.findByEventIdAndDeletedFalse(event.id)
        assertEquals(setOf(anna.id, ben.id), invitations.map { it.userId }.toSet())
        assertTrue(invitations.all { it.status == InvitationStatus.PENDING && it.invitedAt == clock.instant() })
    }

    @Test
    fun `creating needs EVENT_EDIT, EVENT_VIEW is not enough`() {
        assertStatus(HttpStatus.FORBIDDEN) { lesson(by = viewer) }
    }

    @Test
    fun `event needs at least one own horse and an end after the start`() {
        assertStatus(HttpStatus.BAD_REQUEST) { lesson(horses = emptyList()) }
        assertStatus(HttpStatus.BAD_REQUEST) { lesson(horses = listOf(testHorse(stableId = OTHER_STABLE_ID).also { horseRepository.save(it) }.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.createEvent(teacher, "X", "", start, start.minusSeconds(1), listOf(blitz.id), emptyList()) }
    }

    @Test
    fun `invitees must be live users of the own stable`() {
        assertStatus(HttpStatus.BAD_REQUEST) { lesson(invitees = listOf(foreigner.id)) }
    }

    // Edit, delete

    @Test
    fun `creator and admin edit, other teachers cannot`() {
        val event = lesson()

        service.updateEvent(teacher, event.id, "Springstunde", "", start, end, listOf(blitz.id))
        service.updateEvent(admin, event.id, "Dressur", "", start, end, listOf(blitz.id))
        assertEquals("Dressur", eventRepository.findById(event.id)!!.title)

        assertStatus(HttpStatus.FORBIDDEN) { service.updateEvent(otherTeacher, event.id, "X", "", start, end, listOf(blitz.id)) }
        assertStatus(HttpStatus.FORBIDDEN) { service.deleteEvent(otherTeacher, event.id) }
    }

    @Test
    fun `deleting an event soft deletes it and its invitations`() {
        val event = lesson()

        service.deleteEvent(teacher, event.id)

        assertTrue(eventRepository.findById(event.id)!!.deleted)
        assertTrue(invitationRepository.findByEventIdAndDeletedFalse(event.id).isEmpty())
    }

    // Invitations

    @Test
    fun `invitee accepts or declines only their own invitation`() {
        val event = lesson()

        service.respond(anna, invitationOf(event, anna).id, accepted = true)
        service.respond(ben, invitationOf(event, ben).id, accepted = false)

        assertEquals(InvitationStatus.ACCEPTED, invitationOf(event, anna).status)
        assertEquals(clock.instant(), invitationOf(event, anna).respondedAt)
        assertEquals(InvitationStatus.DECLINED, invitationOf(event, ben).status)
        assertStatus(HttpStatus.FORBIDDEN) { service.respond(anna, invitationOf(event, ben).id, accepted = true) }
        assertStatus(HttpStatus.FORBIDDEN) { service.respond(teacher, invitationOf(event, ben).id, accepted = true) }
    }

    @Test
    fun `creator replaces a declined invitee (EVT-4)`() {
        val event = lesson()
        service.respond(ben, invitationOf(event, ben).id, accepted = false)

        service.removeInvitation(teacher, invitationOf(event, ben).id)
        service.invite(teacher, event.id, listOf(clara.id))

        val invited = invitationRepository.findByEventIdAndDeletedFalse(event.id).map { it.userId }.toSet()
        assertEquals(setOf(anna.id, clara.id), invited)
    }

    @Test
    fun `inviting an already invited user changes nothing`() {
        val event = lesson()

        service.invite(teacher, event.id, listOf(anna.id))

        assertEquals(2, invitationRepository.findByEventIdAndDeletedFalse(event.id).size)
    }

    @Test
    fun `only creator and admin manage invitations`() {
        val event = lesson()

        assertStatus(HttpStatus.FORBIDDEN) { service.invite(otherTeacher, event.id, listOf(clara.id)) }
        assertStatus(HttpStatus.FORBIDDEN) { service.removeInvitation(anna, invitationOf(event, ben).id) }
        service.invite(admin, event.id, listOf(clara.id))
    }

    @Test
    fun `foreign or deleted events are not found`() {
        val event = lesson()
        service.deleteEvent(teacher, event.id)
        val foreign = eventRepository.save(event.copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID, deleted = false))

        for (id in listOf(event.id, foreign.id)) {
            assertStatus(HttpStatus.NOT_FOUND) { service.updateEvent(admin, id, "X", "", start, end, listOf(blitz.id)) }
            assertStatus(HttpStatus.NOT_FOUND) { service.invite(admin, id, listOf(clara.id)) }
        }
    }

    // Sync

    private fun syncedEventIds(user: User, since: Long = 0) = service.syncEvents(user, since, 400).updatedEntries.map { it.id }.toSet()

    private fun syncedInvitationUsers(user: User, since: Long = 0) =
        service.syncInvitations(user, since, 400).updatedEntries.map { it.userId }.toSet()

    @Test
    fun `invitees see their events with the full invitation list, others see nothing`() {
        val event = lesson()

        assertEquals(setOf(event.id.toHexString()), syncedEventIds(anna))
        assertEquals(setOf(anna.id.toHexString(), ben.id.toHexString()), syncedInvitationUsers(anna))
        assertEquals(emptySet(), syncedEventIds(clara))
        assertEquals(emptySet(), syncedInvitationUsers(clara))
    }

    @Test
    fun `creator and EVENT_VIEW see the event and its invitations`() {
        val event = lesson()

        assertEquals(setOf(event.id.toHexString()), syncedEventIds(teacher))
        assertEquals(setOf(event.id.toHexString()), syncedEventIds(viewer))
        assertEquals(2, syncedInvitationUsers(viewer).size)
    }

    @Test
    fun `a newly invited user gets the older event and all its invitations on the next sync`() {
        val event = lesson()
        val claraEvents = service.syncEvents(clara, 0, 400).newVersion
        val claraInvitations = service.syncInvitations(clara, 0, 400).newVersion

        service.invite(teacher, event.id, listOf(clara.id))

        assertEquals(setOf(event.id.toHexString()), syncedEventIds(clara, claraEvents))
        assertEquals(setOf(anna.id, ben.id, clara.id).map { it.toHexString() }.toSet(), syncedInvitationUsers(clara, claraInvitations))
    }

    @Test
    fun `a removed invitee gets the event and its invitations as deleted`() {
        val event = lesson()
        val benEvents = service.syncEvents(ben, 0, 400).newVersion
        val benInvitations = service.syncInvitations(ben, 0, 400).newVersion

        service.removeInvitation(teacher, invitationOf(event, ben).id)

        assertEquals(listOf(event.id.toHexString()), service.syncEvents(ben, benEvents, 400).deletedEntries)
        assertEquals(2, service.syncInvitations(ben, benInvitations, 400).deletedEntries.size)
    }
}
