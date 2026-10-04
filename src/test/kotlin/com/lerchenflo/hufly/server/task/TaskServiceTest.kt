package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeTaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TSK-1..TSK-4, BIZ-4 */
class TaskServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val taskRepository = FakeTaskRepository()
    private val horseRepository = FakeHorseRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val taskService = TaskService(
        taskRepository, FakeTaskOccurrenceRepository(), userRepository, horseRepository, accessService, versionCounterService, clock,
    )

    private val admin = testUser()
    private val plannerTag = testTag(permissions = setOf(Permission.TASK_EDIT))
    private val planner = testUser(roleTagIds = listOf(plannerTag.id))
    private val viewerTag = testTag(permissions = setOf(Permission.TASK_VIEW))
    private val viewer = testUser(roleTagIds = listOf(viewerTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)
    private val due = Instant.parse("2026-10-05T07:00:00Z")

    @BeforeTest
    fun setUp() {
        listOf(admin, planner, viewer, anna, ben, foreigner).forEach { userRepository.save(it) }
        tagRepository.save(plannerTag)
        tagRepository.save(viewerTag)
        stableRepository.save(testStable(adminUserId = admin.id))
        horseRepository.save(blitz)
        clock.advance(Duration.ofDays(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private val blitz = testHorse()

    private fun create(
        by: com.lerchenflo.hufly.server.user.model.User = planner,
        assignees: List<ObjectId> = listOf(anna.id, ben.id),
        horses: List<ObjectId> = emptyList(),
    ) = taskService.createTask(by, "Misten", "Box 3", due, assignees, horses)

    private fun stored(id: ObjectId) = taskRepository.findById(id)!!

    @Test
    fun `member with TASK_EDIT creates a task with several assignees`() {
        val task = create()

        val stored = stored(task.id)
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals(listOf(anna.id, ben.id), stored.assigneeUserIds)
        assertEquals(planner.id, stored.createdByUserId)
        assertEquals(due, stored.dueAt)
        assertEquals(clock.instant(), stored.updatedAt)
        assertEquals(1, stored.version)
        assertNull(stored.doneByUserId)
    }

    @Test
    fun `creating without TASK_EDIT is forbidden, TASK_VIEW is not enough`() {
        assertStatus(HttpStatus.FORBIDDEN) { create(by = viewer) }
    }

    @Test
    fun `assignees must be live users of the own stable, at least one`() {
        val removed = userRepository.save(testUser(deleted = true))

        assertStatus(HttpStatus.BAD_REQUEST) { create(assignees = listOf(foreigner.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { create(assignees = listOf(removed.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { create(assignees = emptyList()) }
    }

    @Test
    fun `every write takes a new version`() {
        val task = create()

        taskService.updateTask(planner, task.id, "Misten", "Box 4", due, listOf(anna.id), emptyList())
        assertEquals(2, stored(task.id).version)
        taskService.setDone(anna, task.id, true)
        assertEquals(3, stored(task.id).version)
        taskService.deleteTask(planner, task.id)
        assertEquals(4, stored(task.id).version)
        assertTrue(stored(task.id).deleted)
    }

    @Test
    fun `edit changes fields and keeps the done state`() {
        val task = create()
        taskService.setDone(anna, task.id, true)

        taskService.updateTask(planner, task.id, "Füttern", "", due.plusSeconds(3600), listOf(ben.id), emptyList())

        val stored = stored(task.id)
        assertEquals("Füttern", stored.title)
        assertEquals(listOf(ben.id), stored.assigneeUserIds)
        assertEquals(anna.id, stored.doneByUserId)
    }

    @Test
    fun `any assignee ticks the task done`() {
        val task = create()

        taskService.setDone(ben, task.id, true)

        val stored = stored(task.id)
        assertEquals(ben.id, stored.doneByUserId)
        assertEquals(clock.instant(), stored.doneAt)
        assertEquals(ben.id, stored.updatedBy)
    }

    @Test
    fun `unticking clears who and when`() {
        val task = create()
        taskService.setDone(anna, task.id, true)

        taskService.setDone(anna, task.id, false)

        assertNull(stored(task.id).doneByUserId)
        assertNull(stored(task.id).doneAt)
    }

    @Test
    fun `non assignees without TASK_EDIT cannot tick, TASK_EDIT can`() {
        val task = create(assignees = listOf(anna.id))

        assertStatus(HttpStatus.FORBIDDEN) { taskService.setDone(ben, task.id, true) }
        assertStatus(HttpStatus.FORBIDDEN) { taskService.setDone(viewer, task.id, true) }
        taskService.setDone(planner, task.id, true)
    }

    @Test
    fun `tasks of another stable or deleted tasks are not found`() {
        val task = create()
        taskService.deleteTask(planner, task.id)
        val foreign = taskRepository.save(stored(task.id).copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID, deleted = false))

        for (id in listOf(task.id, foreign.id)) {
            assertStatus(HttpStatus.NOT_FOUND) { taskService.updateTask(planner, id, "X", "", due, listOf(anna.id), emptyList()) }
            assertStatus(HttpStatus.NOT_FOUND) { taskService.deleteTask(planner, id) }
            assertStatus(HttpStatus.NOT_FOUND) { taskService.setDone(planner, id, true) }
        }
    }

    @Test
    fun `members without TASK_EDIT cannot edit or delete`() {
        val task = create()

        assertStatus(HttpStatus.FORBIDDEN) { taskService.updateTask(anna, task.id, "X", "", due, listOf(anna.id), emptyList()) }
        assertStatus(HttpStatus.FORBIDDEN) { taskService.deleteTask(anna, task.id) }
    }

    // Sync

    @Test
    fun `sync without TASK_VIEW returns only own tasks and others as deleted ids`() {
        val mine = create(assignees = listOf(anna.id))
        val other = create(assignees = listOf(ben.id))

        val result = taskService.sync(anna, since = 0, pageSize = 400)

        assertEquals(listOf(mine.id.toHexString()), result.updatedEntries.map { it.id })
        assertEquals(listOf(other.id.toHexString()), result.deletedEntries)
        assertEquals(2, result.newVersion)
    }

    @Test
    fun `sync with TASK_VIEW returns all tasks of the stable`() {
        create(assignees = listOf(anna.id))
        create(assignees = listOf(ben.id))

        assertEquals(2, taskService.sync(viewer, since = 0, pageSize = 400).updatedEntries.size)
    }

    @Test
    fun `sync returns only changes after since, in pages`() {
        val first = create()
        create()
        create()

        val page = taskService.sync(viewer, since = 1, pageSize = 1)

        assertEquals(2, page.newVersion)
        assertTrue(page.moreEntries)
        assertTrue(page.updatedEntries.none { it.id == first.id.toHexString() })
    }

    @Test
    fun `removed assignee gets the task as deleted on the next sync`() {
        val task = create(assignees = listOf(anna.id, ben.id))
        val before = taskService.sync(ben, since = 0, pageSize = 400).newVersion

        taskService.updateTask(planner, task.id, "Misten", "", due, listOf(anna.id), emptyList())

        assertEquals(listOf(task.id.toHexString()), taskService.sync(ben, since = before, pageSize = 400).deletedEntries)
    }

    @Test
    fun `sync never returns tasks of another stable`() {
        create()

        val result = taskService.sync(foreigner.copy(), since = 0, pageSize = 400)

        assertEquals(emptyList(), result.updatedEntries)
        assertEquals(emptyList(), result.deletedEntries)
    }

    @Test
    fun `tasks carry optional horses of the own stable (HOR-6)`() {
        val task = create(horses = listOf(blitz.id))

        assertEquals(listOf(blitz.id), stored(task.id).horseIds)

        taskService.updateTask(planner, task.id, "Misten", "", due, listOf(anna.id), emptyList())
        assertEquals(emptyList(), stored(task.id).horseIds)
    }

    @Test
    fun `task horses must be live horses of the own stable`() {
        val foreign = horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val deleted = horseRepository.save(testHorse(deleted = true))

        assertStatus(HttpStatus.BAD_REQUEST) { create(horses = listOf(foreign.id)) }
        assertStatus(HttpStatus.BAD_REQUEST) { create(horses = listOf(deleted.id)) }
    }

    @Test
    fun `a retried task create answers the first task`() {
        val first = taskService.createTask(planner, "Misten", "", due, listOf(anna.id), emptyList(), clientId = "c1")

        assertEquals(first, taskService.createTask(planner, "Misten", "", due, listOf(anna.id), emptyList(), clientId = "c1"))
        assertEquals(1, taskRepository.tasks.size)
    }
}
