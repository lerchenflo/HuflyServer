package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.picture.FakePictureStore
import com.lerchenflo.hufly.server.core.picture.PictureKind
import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.repository.*
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.web.server.ResponseStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Operator deletes a stable with all of its data (BIZ-1). */
class StableDeletionServiceTest {
    private val stables = FakeStableRepository()
    private val users = FakeUserRepository()
    private val tokens = FakeRefreshTokenRepository()
    private val joinRequests = FakeJoinRequestRepository()
    private val settings = FakeUserSettingsRepository()
    private val digestItems = FakeDigestItemRepository()
    private val horses = FakeHorseRepository()
    private val tags = FakeTagRepository()
    private val pictures = FakePictureStore()
    private val sentReminders = com.lerchenflo.hufly.server.repository.FakeSentReminderRepository()
    private val sentEventReminders = com.lerchenflo.hufly.server.repository.FakeSentEventReminderRepository()
    private val lessonOffers = com.lerchenflo.hufly.server.repository.FakeLessonOfferRepository()
    private val lessonLocks = com.lerchenflo.hufly.server.repository.FakeLessonLockRepository()
    private val clock = com.lerchenflo.hufly.server.core.security.MutableClock(1_000L)

    private val service = StableDeletionService(
        stables, users, joinRequests, digestItems, horses, tags, FakeFoodPlanRepository(), FakeHorseLogRepository(),
        FakePaddockRepository(), FakeHorseGroupRepository(), FakeHorseConflictRepository(), FakePaddockAssignmentRepository(),
        FakeEventRepository(), FakeEventInvitationRepository(), FakeEventOccurrenceRepository(), FakeEventOccurrenceAnswerRepository(),
        FakeTaskRepository(), FakeTaskOccurrenceRepository(), FakeNoteRepository(), FakeAbsenceRepository(), sentReminders, sentEventReminders, lessonOffers, lessonLocks, pictures, users.accounts, clock,
    )

    private val stableId = ObjectId.get()
    private val otherStableId = ObjectId.get()
    private val admin = testUser(stableId = stableId)
    private val removedMember = testUser(stableId = stableId).copy(deleted = true)
    private val otherUser = testUser(stableId = otherStableId)
    private val horse = testHorse(stableId = stableId)
    private val otherHorse = testHorse(stableId = otherStableId)

    private fun setUp() {
        stables.save(testStable(id = stableId, adminUserId = admin.id, name = "Hof Lerchenfeld"))
        stables.save(testStable(id = otherStableId, adminUserId = otherUser.id, name = "Anderer Hof"))
        listOf(admin, removedMember, otherUser).forEach { user ->
            users.save(user)
            tokens.save(RefreshToken(userId = user.id, hashedToken = "h${user.id}", expiresAt = 0L, createdAt = 0L))
            settings.save(UserSettings(user.id, mapOf("k" to "v"), 0L))
            digestItems.save(DigestItem(userId = user.id, stableId = user.stableId, title = "t", body = "b", createdAt = 0L))
            pictures.save(PictureKind.USER, user.id, byteArrayOf(1))
        }
        listOf(horse, otherHorse).forEach { horses.save(it); pictures.save(PictureKind.HORSE, it.id, byteArrayOf(1)) }
        tags.save(testTag(stableId = stableId))
        tags.save(testTag(stableId = otherStableId))
        listOf(stableId, otherStableId).forEach {
            sentReminders.insert(com.lerchenflo.hufly.server.notification.model.SentReminder(stableId = it, taskId = ObjectId.get(), dueAt = 0L, userId = ObjectId.get()))
            sentEventReminders.insert(com.lerchenflo.hufly.server.notification.model.SentEventReminder(stableId = it, eventId = ObjectId.get(), startAt = 0L, userId = ObjectId.get()))
            lessonOffers.save(
                com.lerchenflo.hufly.server.event.model.LessonOffer(
                    stableId = it, eventId = ObjectId.get(), invitationId = ObjectId.get(), userId = ObjectId.get(), occurrenceStartAt = null, offeredAt = 0L,
                )
            )
            lessonLocks.insert(com.lerchenflo.hufly.server.event.model.LessonLock(ObjectId.get(), it, ObjectId.get(), until = 0L))
        }
    }

    @Test
    fun `deletes the stable with its memberships, pictures, pushes, join requests and all stable data`() {
        setUp()
        val request = joinRequests.save(
            com.lerchenflo.hufly.server.account.model.JoinRequest(
                accountId = ObjectId.get(), stableId = stableId,
                status = com.lerchenflo.hufly.server.account.model.JoinRequestStatus.PENDING, createdAt = 0L, updatedAt = 0L,
            )
        )

        service.deleteStable(stableId, "Hof Lerchenfeld")

        assertNull(stables.findById(stableId))
        assertEquals(listOf(otherUser.id), users.users.map { it.id })
        assertEquals(listOf(otherUser.id), digestItems.items.map { it.userId })
        assertNull(joinRequests.findById(request.id))
        assertEquals(listOf(otherHorse.id), horses.horses.map { it.id })
        assertTrue(tags.tags.all { it.stableId == otherStableId } && tags.tags.size == 1)
        assertEquals(setOf(PictureKind.USER to otherUser.id, PictureKind.HORSE to otherHorse.id), pictures.pictures.keys)
        assertEquals(listOf(otherStableId), sentReminders.reminders.map { it.stableId })
        assertEquals(listOf(otherStableId), sentEventReminders.reminders.map { it.stableId })
        assertEquals(listOf(otherStableId), lessonOffers.offers.map { it.stableId })
        assertEquals(listOf(otherStableId), lessonLocks.locks.values.map { it.stableId })
    }

    @Test
    fun `the members keep their logins, sessions and settings, so they can join another stable`() {
        setUp()

        service.deleteStable(stableId, "Hof Lerchenfeld")

        val logins = listOf(admin, removedMember, otherUser).map { it.accountId }
        assertEquals(logins.toSet(), users.accounts.accounts.map { it.id }.toSet())
        assertEquals(logins.toSet(), tokens.tokens.map { it.userId }.toSet())
        assertEquals(logins.toSet(), settings.settings.keys)
    }

    @Test
    fun `a member's digest items from other stables stay`() {
        setUp()
        digestItems.save(DigestItem(userId = admin.id, stableId = otherStableId, title = "elsewhere", body = "b", createdAt = 0L))

        service.deleteStable(stableId, "Hof Lerchenfeld")

        assertEquals(setOf("elsewhere", "t"), digestItems.items.map { it.title }.toSet())
        assertTrue(digestItems.items.none { it.stableId == stableId })
    }

    @Test
    fun `members whose login another stable created get their row there re-stamped`() {
        setUp()
        val home = users.save(testUser(stableId = otherStableId))
        users.save(testUser(stableId = stableId).copy(accountId = home.accountId))

        service.deleteStable(stableId, "Hof Lerchenfeld")

        assertEquals(1_000L, users.findById(home.id)!!.updatedAt)
    }

    @Test
    fun `a wrong confirmation name deletes nothing`() {
        setUp()

        val e = assertFailsWith<ResponseStatusException> { service.deleteStable(stableId, "Hof") }

        assertEquals(400, e.statusCode.value())
        assertEquals(3, users.users.size)
        assertEquals(2, stables.stables.size)
    }

    @Test
    fun `an unknown stable answers 404`() {
        val e = assertFailsWith<ResponseStatusException> { service.deleteStable(ObjectId.get(), "x") }
        assertEquals(404, e.statusCode.value())
    }
}
