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

    private val service = StableDeletionService(
        stables, users, joinRequests, digestItems, horses, tags, FakeFoodPlanRepository(), FakeHorseLogRepository(),
        FakePaddockRepository(), FakeHorseGroupRepository(), FakeHorseConflictRepository(), FakePaddockAssignmentRepository(),
        FakeEventRepository(), FakeEventInvitationRepository(), FakeEventOccurrenceRepository(), FakeEventOccurrenceAnswerRepository(),
        FakeTaskRepository(), FakeTaskOccurrenceRepository(), FakeNoteRepository(), FakeAbsenceRepository(), pictures,
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
            digestItems.save(DigestItem(userId = user.id, title = "t", body = "b", createdAt = 0L))
            pictures.save(PictureKind.USER, user.id, byteArrayOf(1))
        }
        listOf(horse, otherHorse).forEach { horses.save(it); pictures.save(PictureKind.HORSE, it.id, byteArrayOf(1)) }
        tags.save(testTag(stableId = stableId))
        tags.save(testTag(stableId = otherStableId))
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
