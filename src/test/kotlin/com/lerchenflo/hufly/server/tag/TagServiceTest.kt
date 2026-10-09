package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** TAG-1, TAG-2, TAG-5 */
class TagServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val tagService = TagService(tagRepository, accessService, clock)

    private val admin = testUser()
    private val rider = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.save(admin)
        userRepository.save(rider)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    @Test
    fun `admin creates a role tag with permissions in the own stable`() {
        val tag = tagService.createTag(admin, "Reitlehrer", TagType.USER_ROLE, "#AA3300", setOf(Permission.EVENT_EDIT))

        val stored = tagRepository.findById(tag.id)!!
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals(setOf(Permission.EVENT_EDIT), stored.permissions)
        assertEquals(clock.millis(), stored.updatedAt)
        assertEquals(admin.id, stored.updatedBy)
    }

    @Test
    fun `non role tags cannot carry permissions`() {
        assertStatus(HttpStatus.BAD_REQUEST) {
            tagService.createTag(admin, "Heu", TagType.FOOD, "#00AA00", setOf(Permission.HORSE_EDIT))
        }
    }

    @Test
    fun `member cannot create tags`() {
        assertStatus(HttpStatus.FORBIDDEN) { tagService.createTag(rider, "X", TagType.FOOD, "#000000", emptySet()) }
    }

    @Test
    fun `admin edits name, color and permissions but not the type`() {
        val tag = tagRepository.save(testTag(permissions = setOf(Permission.HORSE_EDIT)))

        tagService.updateTag(admin, tag.id, "Helfer", "#123456", setOf(Permission.TASK_EDIT))

        val stored = tagRepository.findById(tag.id)!!
        assertEquals("Helfer", stored.name)
        assertEquals("#123456", stored.color)
        assertEquals(setOf(Permission.TASK_EDIT), stored.permissions)
        assertEquals(TagType.USER_ROLE, stored.type)
        assertEquals(clock.millis(), stored.updatedAt)
    }

    @Test
    fun `editing permissions of a non role tag is rejected`() {
        val tag = tagRepository.save(testTag(type = TagType.ACTIVITY))

        assertStatus(HttpStatus.BAD_REQUEST) { tagService.updateTag(admin, tag.id, "Impfung", "#000000", setOf(Permission.HORSE_EDIT)) }
    }

    @Test
    fun `admin soft deletes a tag`() {
        val tag = tagRepository.save(testTag())

        tagService.deleteTag(admin, tag.id)

        assertTrue(tagRepository.findById(tag.id)!!.deleted)
        assertEquals(clock.millis(), tagRepository.findById(tag.id)!!.updatedAt)
    }

    @Test
    fun `tags of another stable or deleted tags are not found`() {
        val foreign = tagRepository.save(testTag(stableId = OTHER_STABLE_ID))
        val deleted = tagRepository.save(testTag(deleted = true))

        for (tag in listOf(foreign, deleted)) {
            assertStatus(HttpStatus.NOT_FOUND) { tagService.updateTag(admin, tag.id, "X", "#000000", emptySet()) }
            assertStatus(HttpStatus.NOT_FOUND) { tagService.deleteTag(admin, tag.id) }
        }
    }

    @Test
    fun `member cannot edit or delete tags`() {
        val tag = tagRepository.save(testTag())

        assertStatus(HttpStatus.FORBIDDEN) { tagService.updateTag(rider, tag.id, "X", "#000000", emptySet()) }
        assertStatus(HttpStatus.FORBIDDEN) { tagService.deleteTag(rider, tag.id) }
    }

    @Test
    fun `a retried tag create answers the first tag`() {
        val first = tagService.createTag(admin, "Heu", TagType.FOOD, "#00AA00", emptySet(), clientId = "t1")

        assertEquals(first, tagService.createTag(admin, "Heu", TagType.FOOD, "#00AA00", emptySet(), clientId = "t1"))
        assertEquals(1, tagRepository.tags.size)
    }

    @Test
    fun `admin creates a task category without permissions or interval`() {
        val tag = tagService.createTag(admin, "Füttern", TagType.TASK_CATEGORY, "#00AA00", emptySet(), defaultIntervalDays = 7)

        val stored = tagRepository.findById(tag.id)!!
        assertEquals(TagType.TASK_CATEGORY, stored.type)
        assertEquals(null, stored.defaultIntervalDays)
        assertStatus(HttpStatus.BAD_REQUEST) {
            tagService.createTag(admin, "X", TagType.TASK_CATEGORY, "#000000", setOf(Permission.TASK_EDIT))
        }
    }
}
