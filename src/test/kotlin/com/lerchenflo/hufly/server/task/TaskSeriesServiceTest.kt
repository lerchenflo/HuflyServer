package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
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
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.plusSeconds
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TSK-5: task series, changes to single dates and ticking single dates. */
class TaskSeriesServiceTest {

    private val published = mutableListOf<Any>()
    private fun announced() = published.filterIsInstance<com.lerchenflo.hufly.server.core.notification.NotificationEvent>()

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val taskRepository = FakeTaskRepository()
    private val occurrenceRepository = FakeTaskOccurrenceRepository()
    private val horseRepository = FakeHorseRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val taskService = TaskService(
        taskRepository, occurrenceRepository, userRepository, horseRepository, tagRepository,
        com.lerchenflo.hufly.server.repository.FakePaddockAssignmentRepository(), accessService, versionCounterService, clock,
        ApplicationEventPublisher { published += it },
    )
    private val service = TaskOccurrenceService(
        taskService, taskRepository, occurrenceRepository, horseRepository, accessService, versionCounterService, clock,
    )

    private val admin = testUser()
    private val plannerTag = testTag(permissions = setOf(Permission.TASK_EDIT))
    private val planner = testUser(roleTagIds = listOf(plannerTag.id))
    private val viewerTag = testTag(permissions = setOf(Permission.TASK_VIEW))
    private val viewer = testUser(roleTagIds = listOf(viewerTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val blitz = testHorse()
    private val foreignHorse = testHorse(stableId = OTHER_STABLE_ID)

    // Daily 07:00 UTC, five dates.
    private val first = millis("2026-10-05T07:00:00Z")
    private val second = first + days(1)
    private val daily = Recurrence(RecurrenceFrequency.DAILY, 1, emptyList(), null, 5, "UTC")

    @BeforeTest
    fun setUp() {
        listOf(admin, planner, viewer, anna, ben).forEach { userRepository.save(it) }
        listOf(plannerTag, viewerTag).forEach { tagRepository.save(it) }
        listOf(blitz, foreignHorse).forEach { horseRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun series(assignees: List<ObjectId> = listOf(anna.id), recurrence: Recurrence? = daily): StableTask =
        taskService.createTask(planner, "Misten", "", first, assignees, emptyList(), recurrence = recurrence)

    private fun change(
        cancelled: Boolean = false,
        title: String? = null,
        comment: String? = null,
        dueAt: Long? = null,
        horseIds: List<ObjectId>? = null,
        assigneeUserIds: List<ObjectId>? = null,
    ) = TaskOccurrenceChange(cancelled, title, comment, dueAt, horseIds, assigneeUserIds)

    @Test
    fun `a series keeps its rule and an update without one makes it a single task`() {
        val task = series()
        assertEquals(daily, taskRepository.findById(task.id)!!.recurrence)

        taskService.updateTask(planner, task.id, "Misten", "", first, listOf(anna.id), emptyList())

        assertNull(taskRepository.findById(task.id)!!.recurrence)
    }

    @Test
    fun `a series is ticked date by date, not as a whole`() {
        val task = series()

        assertStatus(HttpStatus.BAD_REQUEST) { taskService.setDone(anna, task.id, true) }
    }

    @Test
    fun `the same change twice keeps one row and leaves the tick alone`() {
        val task = series()
        service.setDone(anna, task.id, second, true)

        service.putOccurrence(planner, task.id, second, change(comment = "Mehr Heu"))
        val again = service.putOccurrence(planner, task.id, second, change(comment = "Mehr Heu", horseIds = emptyList()))

        assertEquals(listOf(again.id), occurrenceRepository.occurrences.map { it.id })
        assertEquals("Mehr Heu", again.comment)
        assertEquals(emptyList(), again.horseIds)
        assertEquals(anna.id, again.doneByUserId)
    }

    @Test
    fun `changing dates needs TASK_EDIT, a series and a date of it`() {
        val task = series()
        val single = series(recurrence = null)

        assertStatus(HttpStatus.FORBIDDEN) { service.putOccurrence(anna, task.id, second, change()) }
        assertStatus(HttpStatus.NOT_FOUND) { service.putOccurrence(planner, ObjectId.get(), second, change()) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(planner, single.id, first, change()) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(planner, task.id, first + days(5), change()) }
    }

    @Test
    fun `invalid changes answer 400`() {
        val task = series()
        val invalid = listOf(
            change(title = ""),
            change(title = "x".repeat(201)),
            change(comment = "x".repeat(5001)),
            change(dueAt = 32_503_680_000_001),
            change(horseIds = listOf(foreignHorse.id)),
            change(horseIds = List(51) { blitz.id }),
        )

        invalid.forEach { assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(planner, task.id, second, it) } }
    }

    @Test
    fun `ticking keeps the change, unticking clears the tick`() {
        val task = series()
        service.putOccurrence(planner, task.id, second, change(title = "Misten Box 3"))

        val done = service.setDone(anna, task.id, second, true)
        assertEquals(anna.id, done.doneByUserId)
        assertEquals(clock.millis(), done.doneAt)
        assertEquals("Misten Box 3", done.title)

        val undone = service.setDone(planner, task.id, second, false)
        assertNull(undone.doneByUserId)
        assertNull(undone.doneAt)
    }

    @Test
    fun `ticking needs being an assignee or TASK_EDIT and a live date`() {
        val task = series()
        service.putOccurrence(planner, task.id, second, change(cancelled = true))

        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(ben, task.id, first, true) }
        assertStatus(HttpStatus.CONFLICT) { service.setDone(anna, task.id, second, true) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.setDone(anna, task.id, first.plusSeconds(1), true) }
        assertEquals(planner.id, service.setDone(planner, task.id, first, true).doneByUserId)
    }

    @Test
    fun `dates are seen by assignees and TASK_VIEW, others get them as deleted`() {
        val task = series()
        val occurrence = service.putOccurrence(planner, task.id, second, change(comment = "Mehr Heu"))

        assertEquals(1, service.sync(anna, 0, 100).updatedEntries.size)
        assertEquals(1, service.sync(viewer, 0, 100).updatedEntries.size)
        assertEquals(listOf(occurrence.id.toHexString()), service.sync(ben, 0, 100).deletedEntries)
    }

    @Test
    fun `a new assignee pulls existing dates from where they synced before`() {
        val task = series()
        service.putOccurrence(planner, task.id, second, change(comment = "Mehr Heu"))
        val before = service.sync(ben, 0, 100).newVersion

        taskService.updateTask(planner, task.id, "Misten", "", first, listOf(anna.id, ben.id), emptyList(), recurrence = daily)

        assertEquals(listOf("Mehr Heu"), service.sync(ben, before, 100).updatedEntries.map { it.comment })
    }

    @Test
    fun `deleting the series delivers tombstones for its dates`() {
        val task = series()
        val occurrence = service.putOccurrence(planner, task.id, second, change(comment = "Mehr Heu"))
        val before = service.sync(planner, 0, 100).newVersion

        taskService.deleteTask(planner, task.id)

        assertEquals(listOf(occurrence.id.toHexString()), service.sync(planner, before, 100).deletedEntries)
        assertTrue(occurrenceRepository.findByTaskIdAndDeletedFalse(task.id).isEmpty())
    }

    // Other assignees for a single date

    @Test
    fun `a date names its own assignees until a change without them`() {
        val task = series()

        assertEquals(listOf(ben.id), service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id))).assigneeUserIds)
        assertNull(service.putOccurrence(planner, task.id, second, change()).assigneeUserIds)
    }

    @Test
    fun `date assignees must be distinct live users of the stable`() {
        val task = series()
        val foreigner = testUser(stableId = OTHER_STABLE_ID).also { userRepository.save(it) }

        listOf(emptyList(), listOf(ObjectId.get()), listOf(foreigner.id), listOf(ben.id, ben.id)).forEach {
            assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(planner, task.id, second, change(assigneeUserIds = it)) }
        }
    }

    @Test
    fun `the stand-in ticks the date, the replaced series assignee cannot`() {
        val task = series(assignees = listOf(anna.id))
        service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id)))

        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(anna, task.id, second, true) }
        assertEquals(ben.id, service.setDone(ben, task.id, second, true).doneByUserId)
        assertEquals(anna.id, service.setDone(anna, task.id, first, true).doneByUserId)
        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(ben, task.id, first, true) }
    }

    @Test
    fun `a stand-in sees the series and its dates and pulls them from where they synced before`() {
        val task = series(assignees = listOf(anna.id))
        service.putOccurrence(planner, task.id, first, change(comment = "Mehr Heu"))
        val tasksBefore = taskService.sync(ben, 0, 100).newVersion
        val datesBefore = service.sync(ben, 0, 100).newVersion
        assertTrue(taskService.sync(ben, 0, 100).updatedEntries.isEmpty())

        service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id)))

        assertEquals(listOf(task.id.toHexString()), taskService.sync(ben, tasksBefore, 100).updatedEntries.map { it.id })
        assertEquals(2, service.sync(ben, datesBefore, 100).updatedEntries.size)
    }

    @Test
    fun `a stand-in who no longer covers a date gets the series as deleted`() {
        val task = series(assignees = listOf(anna.id))
        service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id)))
        val before = taskService.sync(ben, 0, 100).newVersion

        service.putOccurrence(planner, task.id, second, change())

        assertEquals(listOf(task.id.toHexString()), taskService.sync(ben, before, 100).deletedEntries)
    }

    @Test
    fun `an undated task stays undated, needs no date for an update and has no occurrences`() {
        val task = taskService.createTask(planner, "Sattelkammer", "", null, listOf(anna.id), emptyList())
        assertNull(taskRepository.findById(task.id)!!.dueAt)

        val dated = series(recurrence = null)
        taskService.updateTask(planner, dated.id, "Misten", "", null, listOf(anna.id), emptyList())
        assertNull(taskRepository.findById(dated.id)!!.dueAt)

        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(planner, task.id, first, change()) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.setDone(anna, task.id, first, true) }
        assertEquals(anna.id, taskService.setDone(anna, task.id, true).doneByUserId)
    }

    @Test
    fun `a repeating task needs a date`() {
        assertStatus(HttpStatus.BAD_REQUEST) {
            taskService.createTask(planner, "Misten", "", null, listOf(anna.id), emptyList(), recurrence = daily)
        }
        val task = series()
        assertStatus(HttpStatus.BAD_REQUEST) {
            taskService.updateTask(planner, task.id, "Misten", "", null, listOf(anna.id), emptyList(), recurrence = daily)
        }
    }

    private fun rotating(assignees: List<ObjectId> = listOf(anna.id, ben.id), recurrence: Recurrence? = daily) =
        taskService.createTask(planner, "Misten", "", first, assignees, emptyList(), recurrence = recurrence, rotatesAssignees = true)

    @Test
    fun `rotation is stored only for a series with at least two assignees`() {
        assertTrue(taskRepository.findById(rotating().id)!!.rotatesAssignees)
        assertEquals(false, taskRepository.findById(rotating(assignees = listOf(anna.id)).id)!!.rotatesAssignees)
        assertEquals(false, taskRepository.findById(rotating(recurrence = null).id)!!.rotatesAssignees)

        val task = rotating()
        taskService.updateTask(planner, task.id, "Misten", "", first, listOf(anna.id, ben.id), emptyList(), recurrence = daily)
        assertEquals(false, taskRepository.findById(task.id)!!.rotatesAssignees)
    }

    @Test
    fun `each date of a rotating series is ticked by its turn's assignee, a stand-in or TASK_EDIT`() {
        val task = rotating()
        val third = second + days(1)

        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(ben, task.id, first, true) }
        assertEquals(anna.id, service.setDone(anna, task.id, first, true).doneByUserId)
        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(anna, task.id, second, true) }
        assertEquals(ben.id, service.setDone(ben, task.id, second, true).doneByUserId)
        assertEquals(anna.id, service.setDone(anna, task.id, third, true).doneByUserId)
        assertEquals(planner.id, service.setDone(planner, task.id, third, true).doneByUserId)

        service.putOccurrence(planner, task.id, third, change(assigneeUserIds = listOf(ben.id)))
        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(anna, task.id, third, false) }
        assertEquals(ben.id, service.setDone(ben, task.id, third, true).doneByUserId)
        assertStatus(HttpStatus.FORBIDDEN) { service.setDone(anna, task.id, third + days(1), true) }
    }

    @Test
    fun `a stand-in for one date is announced once`() {
        val task = series()
        service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id)))
        service.putOccurrence(planner, task.id, second, change(assigneeUserIds = listOf(ben.id), comment = "Mehr Heu"))

        assertEquals(
            com.lerchenflo.hufly.server.core.notification.TaskAssigned(com.lerchenflo.hufly.server.testdata.STABLE_ID, planner.id, task.id, listOf(ben.id), second),
            announced().drop(1).single(),
        )
    }
}
