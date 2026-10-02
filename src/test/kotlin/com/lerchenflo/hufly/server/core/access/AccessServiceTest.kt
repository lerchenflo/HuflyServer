package com.lerchenflo.hufly.server.core.access

import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TAG-1, TAG-3, TAG-4, TAG-6, BIZ-4 */
class AccessServiceTest {

    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)

    private val admin = testUser()
    private val rider = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.save(admin)
        userRepository.save(rider)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    @Test
    fun `requester returns the user of the id`() {
        assertEquals(rider, accessService.requester(rider.id))
    }

    @Test
    fun `requester of a deleted user is unauthorized`() {
        userRepository.save(rider.copy(deleted = true))

        assertStatus(HttpStatus.UNAUTHORIZED) { accessService.requester(rider.id) }
    }

    @Test
    fun `requester of an unknown id is unauthorized`() {
        assertStatus(HttpStatus.UNAUTHORIZED) { accessService.requester(testUser().id) }
    }

    @Test
    fun `stable admin is admin`() {
        assertTrue(accessService.isAdmin(admin))
        assertFalse(accessService.isAdmin(rider))
    }

    @Test
    fun `admin has every permission without any tag`() {
        assertEquals(Permission.entries.toSet(), accessService.effectivePermissions(admin))
    }

    @Test
    fun `member has the union of the permissions of their role tags`() {
        val trainer = tagRepository.save(testTag(permissions = setOf(Permission.EVENT_VIEW, Permission.EVENT_CREATE_INVITE)))
        val helper = tagRepository.save(testTag(permissions = setOf(Permission.TASK_CREATE_ASSIGN)))
        tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT)))
        val user = userRepository.save(rider.copy(roleTagIds = listOf(trainer.id, helper.id)))

        assertEquals(
            setOf(Permission.EVENT_VIEW, Permission.EVENT_CREATE_INVITE, Permission.TASK_CREATE_ASSIGN),
            accessService.effectivePermissions(user),
        )
    }

    @Test
    fun `deleted, non role and foreign stable tags grant nothing`() {
        val deleted = tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT), deleted = true))
        val food = tagRepository.save(testTag(type = TagType.FOOD, permissions = setOf(Permission.FOODPLAN_EDIT)))
        val foreign = tagRepository.save(testTag(stableId = OTHER_STABLE_ID, permissions = setOf(Permission.PADDOCK_PLAN)))
        val user = userRepository.save(rider.copy(roleTagIds = listOf(deleted.id, food.id, foreign.id)))

        assertEquals(emptySet(), accessService.effectivePermissions(user))
    }

    @Test
    fun `requirePermission passes with the permission and is forbidden without`() {
        val tag = tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT)))
        val user = userRepository.save(rider.copy(roleTagIds = listOf(tag.id)))

        accessService.requirePermission(user, Permission.HORSE_EDIT)
        assertStatus(HttpStatus.FORBIDDEN) { accessService.requirePermission(user, Permission.PADDOCK_PLAN) }
    }

    @Test
    fun `requireAdmin is forbidden for members, even with every permission`() {
        val tag = tagRepository.save(testTag(permissions = Permission.entries.toSet()))
        val user = userRepository.save(rider.copy(roleTagIds = listOf(tag.id)))

        accessService.requireAdmin(admin)
        assertStatus(HttpStatus.FORBIDDEN) { accessService.requireAdmin(user) }
    }

    @Test
    fun `user of a stable without stable document is not admin`() {
        val stranger = userRepository.save(testUser(stableId = OTHER_STABLE_ID))

        assertFalse(accessService.isAdmin(stranger))
    }
}
