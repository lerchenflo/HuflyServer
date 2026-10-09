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
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.minutes
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
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val pictureStore = com.lerchenflo.hufly.server.core.picture.FakePictureStore()
    private val horseRepository = com.lerchenflo.hufly.server.repository.FakeHorseRepository()
    private val absenceRepository = com.lerchenflo.hufly.server.repository.FakeAbsenceRepository()
    private val absenceService = com.lerchenflo.hufly.server.absence.AbsenceService(
        absenceRepository, userRepository, accessService,
        com.lerchenflo.hufly.server.core.sync.VersionCounterService(com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore()), clock,
    )
    private val userSettingsRepository = com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository()
    private val digestItemRepository = com.lerchenflo.hufly.server.repository.FakeDigestItemRepository()
    private val joinRequestRepository = com.lerchenflo.hufly.server.repository.FakeJoinRequestRepository()
    private val stableDeletionService = com.lerchenflo.hufly.server.stable.StableDeletionService(
        stableRepository, userRepository, joinRequestRepository, digestItemRepository, horseRepository, tagRepository,
        com.lerchenflo.hufly.server.repository.FakeFoodPlanRepository(), com.lerchenflo.hufly.server.repository.FakeHorseLogRepository(), com.lerchenflo.hufly.server.repository.FakePaddockRepository(), com.lerchenflo.hufly.server.repository.FakeHorseGroupRepository(),
        com.lerchenflo.hufly.server.repository.FakeHorseConflictRepository(), com.lerchenflo.hufly.server.repository.FakePaddockAssignmentRepository(), com.lerchenflo.hufly.server.repository.FakeEventRepository(),
        com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository(), com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository(), com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository(),
        com.lerchenflo.hufly.server.repository.FakeTaskRepository(), com.lerchenflo.hufly.server.repository.FakeTaskOccurrenceRepository(), com.lerchenflo.hufly.server.repository.FakeNoteRepository(), absenceRepository, pictureStore,
    )
    private val userService = UserService(
        userRepository, userRepository.accounts, joinRequestRepository, stableRepository, stableDeletionService, tagRepository, refreshTokenRepository, horseRepository, userSettingsRepository, digestItemRepository,
        absenceService, pictureStore, accessService, hashEncoder, clock,
    )

    private val admin = testUser(email = "admin@hufly.test")
    private val rider = testUser(email = "rider@hufly.test")
    private val foreigner = testUser(email = "foreign@hufly.test", stableId = OTHER_STABLE_ID)
    private val roleTag = testTag()

    @BeforeTest
    fun setUp() {
        listOf(admin, rider, foreigner).forEach { userRepository.save(it) }
        userRepository.saveWithLogin(rider, hashEncoder.encode("OldSecret1"))
        stableRepository.save(testStable(adminUserId = admin.id))
        tagRepository.save(roleTag)
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored(id: ObjectId) = userRepository.findById(id)!!

    private fun login(accountId: ObjectId) = userRepository.accounts.findById(accountId)!!

    private fun session(userId: ObjectId) = refreshTokenRepository.save(
        RefreshToken(userId = userId, hashedToken = ObjectId.get().toHexString(), expiresAt = Long.MAX_VALUE, createdAt = 0L)
    )

    // Create

    @Test
    fun `admin creates a user in the own stable with a generated password`() {
        val created = userService.createUser(admin, " New@Hufly.test ", "Neu", "+43 1", listOf(roleTag.id))

        val user = stored(created.user.id)
        assertEquals(STABLE_ID, user.stableId)
        assertEquals("new@hufly.test", user.email)
        assertEquals(listOf(roleTag.id), user.roleTagIds)
        assertEquals(clock.millis(), user.updatedAt)
        assertEquals(admin.id, user.updatedBy)
        assertTrue(created.generatedPassword.length >= 12)
        assertTrue(hashEncoder.matches(created.generatedPassword, login(user.accountId).hashedPassword))
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
        assertEquals(clock.millis(), user.updatedAt)
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
        assertEquals(clock.millis(), user.updatedAt)
        assertEquals(listOf(adminSession), refreshTokenRepository.tokens)
    }

    @Test
    fun `a deleted user's personal data is wiped and the email freed`() {
        userSettingsRepository.save(com.lerchenflo.hufly.server.user.model.UserSettings(rider.id, mapOf("a" to "b"), 0L))
        digestItemRepository.save(com.lerchenflo.hufly.server.notification.model.DigestItem(userId = rider.id, title = "t", body = "b", createdAt = 0L))
        pictureStore.save(com.lerchenflo.hufly.server.core.picture.PictureKind.USER, rider.id, byteArrayOf(1))

        userService.deleteUser(admin, rider.id)

        val user = stored(rider.id)
        assertEquals("deleted-${rider.id.toHexString()}@deleted.invalid", user.email)
        assertEquals(DELETED_USER_NAME, user.displayName)
        assertEquals(null, user.phoneNumber)
        assertEquals(emptyList(), user.roleTagIds)
        assertTrue(!hashEncoder.matches("OldSecret1", login(rider.id).hashedPassword))
        assertTrue(login(rider.id).deleted)
        assertEquals(null, userSettingsRepository.findById(rider.id))
        assertTrue(digestItemRepository.items.isEmpty())
        assertEquals(null, pictureStore.load(com.lerchenflo.hufly.server.core.picture.PictureKind.USER, rider.id))
        userService.createUser(admin, "rider@hufly.test", "Neu", null, emptyList())
    }

    @Test
    fun `member deletes the own account with the password`() {
        session(rider.id)
        val adminSession = session(admin.id)

        userService.deleteOwnAccount(login(rider.id), "OldSecret1")

        val user = stored(rider.id)
        assertTrue(user.deleted)
        assertEquals(rider.id, user.updatedBy)
        assertEquals(DELETED_USER_NAME, user.displayName)
        assertEquals(listOf(adminSession), refreshTokenRepository.tokens)
    }

    @Test
    fun `own account deletion with a wrong password is rejected without 401`() {
        val e = assertFailsWith<com.lerchenflo.hufly.server.core.CodedException> { userService.deleteOwnAccount(login(rider.id), "wrong") }
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        assertEquals("WRONG_PASSWORD", e.code)
        assertTrue(!stored(rider.id).deleted)
    }

    @Test
    fun `the stable admin cannot delete the own account`() {
        userRepository.saveWithLogin(admin, hashEncoder.encode("AdminSecret1"))
        val e = assertFailsWith<com.lerchenflo.hufly.server.core.CodedException> { userService.deleteOwnAccount(login(admin.id), "AdminSecret1") }
        assertEquals(HttpStatus.CONFLICT, e.statusCode)
        assertEquals("STABLE_ADMIN", e.code)
        assertTrue(!stored(admin.id).deleted)
    }

    // Self-registered members own their login

    private fun selfRegistered(): com.lerchenflo.hufly.server.user.model.User {
        val login = userRepository.accounts.save(
            com.lerchenflo.hufly.server.testdata.testAccount(email = "self@hufly.test", hashedPassword = hashEncoder.encode("SelfSecret1"))
        )
        return userRepository.save(testUser(email = "self@hufly.test").copy(accountId = login.id))
    }

    @Test
    fun `the admin cannot reset the password of a self-registered member`() {
        val member = selfRegistered()

        val e = assertFailsWith<com.lerchenflo.hufly.server.core.CodedException> { userService.resetPassword(admin, member.id) }

        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
        assertEquals("SELF_REGISTERED", e.code)
        assertTrue(hashEncoder.matches("SelfSecret1", login(member.accountId).hashedPassword))
    }

    @Test
    fun `the admin cannot change the login email of a self-registered member, other fields yes`() {
        val member = selfRegistered()

        val e = assertFailsWith<com.lerchenflo.hufly.server.core.CodedException> {
            userService.updateUser(admin, member.id, "other@hufly.test", "Self", null, emptyList())
        }
        assertEquals("SELF_REGISTERED", e.code)

        userService.updateUser(admin, member.id, "self@hufly.test", "Neuer Name", "+43 1", listOf(roleTag.id))
        assertEquals("Neuer Name", stored(member.id).displayName)
        assertEquals("self@hufly.test", login(member.accountId).email)
    }

    @Test
    fun `the admin changes the login email of members the stable created`() {
        userService.updateUser(admin, rider.id, "Rider.New@hufly.test", "Rider", null, emptyList())

        assertEquals("rider.new@hufly.test", stored(rider.id).email)
        assertEquals("rider.new@hufly.test", login(rider.accountId).email)
    }

    @Test
    fun `removing a self-registered member keeps their login, which then has no stable`() {
        val member = selfRegistered()
        session(member.accountId)

        userService.deleteUser(admin, member.id)

        assertTrue(stored(member.id).deleted)
        val kept = login(member.accountId)
        assertTrue(!kept.deleted)
        assertEquals("self@hufly.test", kept.email)
        assertEquals(1, refreshTokenRepository.tokens.size)
        val e = assertFailsWith<com.lerchenflo.hufly.server.core.CodedException> { accessService.requester(member.accountId) }
        assertEquals("NO_STABLE", e.code)
    }

    @Test
    fun `own profile changes reach the login too`() {
        userService.updateMe(rider, "Rider2@hufly.test", "Reiterin", null)

        assertEquals("rider2@hufly.test", login(rider.accountId).email)
        assertEquals("Reiterin", login(rider.accountId).displayName)
    }

    @Test
    fun `erased addresses cannot be taken`() {
        assertStatus(HttpStatus.BAD_REQUEST) { userService.updateMe(rider, "deleted-1@deleted.invalid", "X", null) }
        assertStatus(HttpStatus.BAD_REQUEST) { userService.createUser(admin, "deleted-2@deleted.invalid", "X", null, emptyList()) }
    }

    @Test
    fun `a member created by the admin gets an own login linked to the membership`() {
        val created = userService.createUser(admin, "linked@hufly.test", "L", null, emptyList())

        val linked = login(created.user.accountId)
        assertEquals("linked@hufly.test", linked.email)
        assertEquals(STABLE_ID, linked.createdByStableId)
        assertEquals(created.user, accessService.requester(linked.id))
    }

    @Test
    fun `an admin alone in the stable deletes the own account together with the stable`() {
        userRepository.save(rider.copy(deleted = true))
        userRepository.saveWithLogin(admin, hashEncoder.encode("AdminSecret1"))
        horseRepository.save(com.lerchenflo.hufly.server.testdata.testHorse())

        userService.deleteOwnAccount(login(admin.id), "AdminSecret1")

        assertEquals(null, stableRepository.findById(STABLE_ID))
        assertTrue(horseRepository.horses.none { it.stableId == STABLE_ID })
        assertTrue(login(admin.id).deleted)
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

        assertTrue(hashEncoder.matches(password, login(rider.id).hashedPassword))
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
        userService.changePassword(login(rider.id), "OldSecret1", "NewSecret1", currentSessionId = null)

        assertTrue(hashEncoder.matches("NewSecret1", login(rider.id).hashedPassword))
    }

    @Test
    fun `password change keeps the current session and ends all others`() {
        val current = session(rider.id)
        session(rider.id)
        val adminSession = session(admin.id)

        userService.changePassword(login(rider.id), "OldSecret1", "NewSecret1", currentSessionId = current.id)

        assertEquals(setOf(current, adminSession), refreshTokenRepository.tokens.toSet())
    }

    @Test
    fun `password change with a wrong old password is rejected without 401`() {
        assertStatus(HttpStatus.BAD_REQUEST) { userService.changePassword(login(rider.id), "wrong", "NewSecret1", currentSessionId = null) }
    }

    // Profile picture (USR-6)

    @Test
    fun `user uploads and removes the own profile picture`() {
        val updated = userService.setMyPicture(rider, com.lerchenflo.hufly.server.core.picture.testPng())

        assertEquals("/users/${rider.id.toHexString()}/picture?v=${updated.updatedAt}", updated.profilePictureUrl)
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

    @Test
    fun `admin sets and removes a member's profile picture`() {
        val updated = userService.setPicture(admin, rider.id, com.lerchenflo.hufly.server.core.picture.testPng())

        assertEquals("/users/${rider.id.toHexString()}/picture?v=${clock.millis()}", stored(rider.id).profilePictureUrl)
        assertEquals(updated, stored(rider.id))
        assertEquals(admin.id, updated.updatedBy)
        assertTrue(userService.picture(rider, rider.id).isNotEmpty())

        clock.advance(minutes(1))
        val cleared = userService.deletePicture(admin, rider.id)
        assertEquals(null, stored(rider.id).profilePictureUrl)
        assertEquals(clock.millis(), cleared.updatedAt)
        assertStatus(HttpStatus.NOT_FOUND) { userService.picture(admin, rider.id) }
    }

    @Test
    fun `only the own stable's admin sets other members' pictures`() {
        val png = com.lerchenflo.hufly.server.core.picture.testPng()
        assertStatus(HttpStatus.FORBIDDEN) { userService.setPicture(rider, admin.id, png) }
        assertStatus(HttpStatus.FORBIDDEN) { userService.deletePicture(rider, admin.id) }
        assertStatus(HttpStatus.NOT_FOUND) { userService.setPicture(admin, foreigner.id, png) }
        assertStatus(HttpStatus.NOT_FOUND) { userService.deletePicture(admin, foreigner.id) }
        assertTrue(pictureStore.pictures.isEmpty())
    }

    @Test
    fun `admin-generated passwords ask for an own one until the user changes it`() {
        val created = userService.createUser(admin, "new@hufly.test", "Neu", null, emptyList())
        val accountId = created.user.accountId
        assertTrue(login(accountId).mustChangePassword)

        userService.changePassword(login(accountId), created.generatedPassword, "MyOwnSecret1", null)
        assertEquals(false, login(accountId).mustChangePassword)

        userService.resetPassword(admin, created.user.id)
        assertTrue(login(accountId).mustChangePassword)
        assertEquals(false, login(rider.id).mustChangePassword)
    }

    @Test
    fun `a deleted member is removed from every horse they co-ride`() {
        val shared = horseRepository.save(com.lerchenflo.hufly.server.testdata.testHorse().copy(coRiderUserIds = listOf(rider.id, admin.id)))
        val untouched = horseRepository.save(com.lerchenflo.hufly.server.testdata.testHorse().copy(coRiderUserIds = listOf(admin.id)))
        clock.advance(minutes(1))

        userService.deleteUser(admin, rider.id)

        val stored = horseRepository.findById(shared.id)!!
        assertEquals(listOf(admin.id), stored.coRiderUserIds)
        assertEquals(clock.millis(), stored.updatedAt)
        assertEquals(admin.id, stored.updatedBy)
        assertEquals(untouched, horseRepository.findById(untouched.id))
    }

    @Test
    fun `a deleted member's absences are deleted`() {
        val absence = absenceService.createAbsence(
            rider, com.lerchenflo.hufly.server.absence.AbsenceService.AbsenceData(rider.id, epochDay(2026, 10, 12), epochDay(2026, 10, 13), ""),
        )

        userService.deleteUser(admin, rider.id)

        assertTrue(absenceRepository.findById(absence.id)!!.deleted)
    }
}
