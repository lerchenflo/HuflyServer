package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
import com.lerchenflo.hufly.server.user.UserSettingsService.PutResult
import org.bson.types.ObjectId
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** OFF-5, OFF-6: a save from another device is never overwritten unseen when the client sends expectedUpdatedAt. */
class UserSettingsServiceTest {

    private val clock = MutableClock()
    private val repository = FakeUserSettingsRepository()
    private val service = UserSettingsService(repository, clock) {}
    private val anna = ObjectId.get()

    private fun saved(result: PutResult) = assertIs<PutResult.Saved>(result).settings
    private fun conflict(result: PutResult) = assertIs<PutResult.Conflict>(result).current

    @Test
    fun `without an expectation the last write wins`() {
        service.put(anna, mapOf("theme" to "dark"), PutCondition.None)
        clock.advance(Duration.ofSeconds(1))

        val result = saved(service.put(anna, mapOf("theme" to "light"), PutCondition.None))

        assertEquals(mapOf("theme" to "light"), result.values)
        assertEquals(clock.instant(), result.updatedAt)
    }

    @Test
    fun `expecting the stored version saves`() {
        val first = saved(service.put(anna, mapOf("theme" to "dark"), PutCondition.None))
        clock.advance(Duration.ofSeconds(1))

        val second = saved(service.put(anna, mapOf("theme" to "light"), PutCondition.Expect(first.updatedAt.toEpochMilli())))

        assertEquals(mapOf("theme" to "light"), repository.findById(anna)!!.values)
        assertEquals(clock.instant(), second.updatedAt)
    }

    @Test
    fun `an outdated expectation answers the current settings and changes nothing`() {
        val first = saved(service.put(anna, mapOf("theme" to "dark"), PutCondition.None))
        clock.advance(Duration.ofSeconds(1))
        val other = saved(service.put(anna, mapOf("theme" to "light"), PutCondition.None))

        val current = conflict(service.put(anna, mapOf("theme" to "blue"), PutCondition.Expect(first.updatedAt.toEpochMilli())))

        assertEquals(other, current)
        assertEquals(mapOf("theme" to "light"), repository.findById(anna)!!.values)
    }

    @Test
    fun `expecting no settings yet saves only the first time`() {
        saved(service.put(anna, mapOf("theme" to "dark"), PutCondition.Expect(null)))

        val current = conflict(service.put(anna, mapOf("theme" to "light"), PutCondition.Expect(null)))

        assertEquals(mapOf("theme" to "dark"), current!!.values)
    }

    @Test
    fun `expecting a version when nothing is stored is a conflict with no current settings`() {
        assertNull(conflict(service.put(anna, mapOf("theme" to "dark"), PutCondition.Expect(123))))
        assertNull(repository.findById(anna))
    }
}
