package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** USR-1, USR-2, USR-5, USR-6, USR-7, BIZ-4, BIZ-5 */
class UserServiceTest {

    private val clock = MutableClock()
    private val hashEncoder = HashEncoder()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val refreshTokenRepository = FakeRefreshTokenRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val pictureStore = com.lerchenflo.hufly.server.core.picture.FakePictureStore()
    private val userService = UserService(userRepository, tagRepository, refreshTokenRepository, pictureStore, accessService, hashEncoder, clock)

    private val admin = testUser(email = "admin@hufly.test")
    private val rider = testUser(email = "rider@hufly.test", hashedPassword = hashEncoder.encode("OldSecret1"))
    private val foreigner = testUser(email = "foreign@hufly.test", stableId = OTHER_STABLE_ID)
    private val roleTag = testTag()

    @BeforeTest
    fun setUp() {
        listOf(admin, rider, foreigner).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        tagRepository.save(roleTag)
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored(id: ObjectId) = userRepository.findById(id)!!

    private fun session(userId: ObjectId) = refreshTokenRepository.save(
        RefreshToken(userId = userId, hashedToken = ObjectId.get().toHexString(), expiresAt = Instant.MAX, createdAt = Instant.EPOCH)
    )

    // Create

    @Test
    fun `admin creates a user in the own stable with a generated password`() {
        val created = userService.createUser(admin, " New@Hufly.test ", "Neu", "+43 1", listOf(roleTag.id))

        val user = stored(created.user.id)
        assertEquals(STABLE_ID, user.stableId)
        assertEquals("new@hufly.test", user.email)
        assertEquals(listOf(roleTag.id), user.roleTagIds)
        assertEquals(clock.instant(), user.updatedAt)
        assertEquals(admin.id, user.updatedBy)
        assertTrue(created.generatedPassword.length >= 12)
        assertTrue(hashEncoder.matches(created.generatedPassword, user.hashedPassword))
    }

    @Test
    fun `generated passwords differ`() {
        val first = userService.createUser(admin, "a@hufly.test", "A", null, emptyList())
        val second = userService.createUser(admin, "b@hufly.test", "B", null, emptyList())

        assertNotEquals(first.generatedPassword, second.generatedPassword)
    }

    @Test
    fun `member cannot create users`() {
        assertStatus(HttpStatus.FORBIDDEN) { userService.createUser(rider, "x@hufly.test", "X", null, emptyList()) }
    }

    @Test
    fun `creating a user with an email taken in any stable conflicts`() {
        assertStatus(HttpStatus.CONFLICT) { userService.createUser(admin, "FOREIGN@hufly.test", "X", null, emptyList()) }
    }

    @Test
    fun `role tags must be live USER_ROLE tags of the own stable`() {
        val foreignTag = tagRepository.save(testTag(stableId = OTHER_STABLE_ID))
        val foodTag = tagRepository.save(testTag(type = TagType.FOOD))
        val deletedTag = tagRepository.save(testTag(deleted = true))

        for (tag in listOf(foreignTag, foodTag, deletedTag)) {
            assertStatus(HttpStatus.BAD_REQUEST) { userService.createUser(admin, "x@hufly.test", "X", null, listOf(tag.id)) }
        }
    }

    // Edit by admin

    @Test
    fun `admin edits every field of a member`() {
        userService.updateUser(admin, rider.id, "Renamed@hufly.test", "Renamed", "+43 2", listOf(roleTag.id))

        val user = stored(rider.id)
        assertEquals("renamed@hufly.test", user.email)
        assertEquals("Renamed", user.displayName)
        assertEquals("+43 2", user.phoneNumber)
        assertEquals(listOf(roleTag.id), user.roleTagIds)
        assertEquals(clock.instant(), user.updatedAt)
        assertEquals(admin.id, user.updatedBy)
    }

    @Test
    fun `admin keeps the own email when editing without conflict`() {
        userService.updateUser(admin, rider.id, "rider@hufly.test", "Same", null, emptyList())

        assertEquals("Same", stored(rider.id).displayName)
    }

    @Test
    fun `editing to an email of another user conflicts`() {
        assertStatus(HttpStatus.CONFLICT) {
            userService.updateUser(admin, rider.id, "admin@hufly.test", "X", null, emptyList())
        }
    }

    @Test
    fun `admin cannot edit users of another stable or deleted users`() {
        val removed = userRepository.save(testUser(deleted = true))

        for (target in listOf(foreigner, removed)) {
            assertStatus(HttpStatus.NOT_FOUND) { userService.updateUser(admin, target.id, "x@hufly.test", "X", null, emptyList()) }
        }
    }

    @Test
    fun `member cannot edit other users`() {
        assertStatus(HttpStatus.FORBIDDEN) { userService.updateUser(rider, admin.id, "x@hufly.test", "X", null, emptyList()) }
    }

    // Delete

    @Test
    fun `admin soft deletes a member and ends their sessions`() {
        session(rider.id)
        val adminSession = session(admin.id)

        userService.deleteUser(admin, rider.id)

        val user = stored(rider.id)
        assertTrue(user.deleted)
        assertEquals(clock.instant(), user.updatedAt)
        assertEquals(listOf(adminSession), refreshTokenRepository.tokens)
    }

    @Test
    fun `admin cannot delete themselves`() {
        assertStatus(HttpStatus.BAD_REQUEST) { userService.deleteUser(admin, admin.id) }
    }

    @Test
    fun `member cannot delete users`() {
        assertStatus(HttpStatus.FORBIDDEN) { userService.deleteUser(rider, admin.id) }
    }

    @Test
    fun `admin cannot delete users of another stable`() {
        assertStatus(HttpStatus.NOT_FOUND) { userService.deleteUser(admin, foreigner.id) }
    }

    // Password reset

    @Test
    fun `admin reset gives a new password and ends the user's sessions`() {
        session(rider.id)

        val password = userService.resetPassword(admin, rider.id)

        assertTrue(hashEncoder.matches(password, stored(rider.id).hashedPassword))
        assertTrue(refreshTokenRepository.tokens.isEmpty())
    }

    @Test
    fun `member cannot reset passwords`() {
        assertStatus(HttpStatus.FORBIDDEN) { userService.resetPassword(rider, admin.id) }
    }

    @Test
    fun `admin cannot reset passwords in another stable`() {
        assertStatus(HttpStatus.NOT_FOUND) { userService.resetPassword(admin, foreigner.id) }
    }

    // Own profile

    @Test
    fun `user edits the own profile except role tags`() {
        userService.updateMe(rider, "Me@hufly.test", "Me", "+43 3")

        val user = stored(rider.id)
        assertEquals("me@hufly.test", user.email)
        assertEquals("Me", user.displayName)
        assertEquals("+43 3", user.phoneNumber)
        assertEquals(rider.roleTagIds, user.roleTagIds)
        assertEquals(rider.id, user.updatedBy)
    }

    @Test
    fun `own email change to a taken email conflicts`() {
        assertStatus(HttpStatus.CONFLICT) { userService.updateMe(rider, "foreign@hufly.test", "Me", null) }
    }

    @Test
    fun `user changes the own password with the old one`() {
        userService.changePassword(rider, "OldSecret1", "NewSecret1", currentSessionId = null)

        assertTrue(hashEncoder.matches("NewSecret1", stored(rider.id).hashedPassword))
    }

    @Test
    fun `password change keeps the current session and ends all others`() {
        val current = session(rider.id)
        session(rider.id)
        val adminSession = session(admin.id)

        userService.changePassword(rider, "OldSecret1", "NewSecret1", currentSessionId = current.id)

        assertEquals(setOf(current, adminSession), refreshTokenRepository.tokens.toSet())
    }

    @Test
    fun `password change with a wrong old password is rejected without 401`() {
        assertStatus(HttpStatus.BAD_REQUEST) { userService.changePassword(rider, "wrong", "NewSecret1", currentSessionId = null) }
    }

    // Profile picture (USR-6)

    @Test
    fun `user uploads and removes the own profile picture`() {
        val updated = userService.setMyPicture(rider, com.lerchenflo.hufly.server.core.picture.testPng())

        assertEquals("/users/${rider.id.toHexString()}/picture?v=${updated.updatedAt.toEpochMilli()}", updated.profilePictureUrl)
        assertEquals(updated, stored(rider.id))
        assertTrue(userService.picture(admin, rider.id).isNotEmpty())

        val cleared = userService.deleteMyPicture(rider)
        assertEquals(null, cleared.profilePictureUrl)
        assertStatus(HttpStatus.NOT_FOUND) { userService.picture(admin, rider.id) }
    }

    @Test
    fun `profile pictures stay inside the stable and go with the user`() {
        userService.setMyPicture(rider, com.lerchenflo.hufly.server.core.picture.testPng())
        val foreigner = userRepository.save(testUser(stableId = com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID))

        assertStatus(HttpStatus.NOT_FOUND) { userService.picture(foreigner, rider.id) }

        userService.deleteUser(admin, rider.id)
        assertStatus(HttpStatus.NOT_FOUND) { userService.picture(admin, rider.id) }
        assertTrue(pictureStore.pictures.isEmpty())
    }
}
