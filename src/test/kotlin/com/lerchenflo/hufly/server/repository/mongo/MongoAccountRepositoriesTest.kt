package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.account.model.JoinRequest
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.JoinRequestRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.testdata.testAccount
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DuplicateKeyException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Logins, memberships, join requests and invite codes against real Mongo with its indexes. */
@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoAccountRepositoriesTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var joinRequestRepository: JoinRequestRepository
    @Autowired lateinit var stableRepository: StableRepository

    private fun request(accountId: ObjectId, stableId: ObjectId = ObjectId.get(), status: JoinRequestStatus = JoinRequestStatus.PENDING, at: Long = 0L) =
        JoinRequest(accountId = accountId, stableId = stableId, status = status, createdAt = at, updatedAt = at)

    @Test
    fun `an email belongs to one login`() {
        accountRepository.insert(testAccount(email = "same@hufly.test"))

        assertFailsWith<DuplicateKeyException> { accountRepository.insert(testAccount(email = "same@hufly.test")) }
        assertEquals("same@hufly.test", accountRepository.findByEmail("same@hufly.test")!!.email)
    }

    @Test
    fun `a login has at most one live membership, removed ones do not count`() {
        val login = ObjectId.get()
        val removed = userRepository.insert(testUser().copy(accountId = login, deleted = true))
        val live = userRepository.insert(testUser().copy(accountId = login))

        assertFailsWith<DuplicateKeyException> { userRepository.insert(testUser().copy(accountId = login)) }
        assertEquals(live.id, userRepository.findFirstByAccountIdAndDeletedFalse(login)!!.id)
        assertTrue(removed.id != live.id)
    }

    @Test
    fun `memberships keep the same email in several rows`() {
        userRepository.insert(testUser(email = "twice@hufly.test"))
        userRepository.insert(testUser(email = "twice@hufly.test"))
    }

    @Test
    fun `one pending join request per login, resolved ones do not count`() {
        val login = ObjectId.get()
        joinRequestRepository.insert(request(login, status = JoinRequestStatus.DECLINED))
        joinRequestRepository.insert(request(login, at = 5L))

        assertFailsWith<DuplicateKeyException> { joinRequestRepository.insert(request(login)) }
        assertEquals(5L, joinRequestRepository.findFirstByAccountIdOrderByCreatedAtDesc(login)!!.createdAt)
    }

    @Test
    fun `resolving a pending request succeeds once and only for its stable`() {
        val stableId = ObjectId.get()
        val pending = joinRequestRepository.insert(request(ObjectId.get(), stableId))

        assertEquals(0L, joinRequestRepository.resolvePending(pending.id, ObjectId.get(), JoinRequestStatus.ACCEPTED, 9L))
        assertEquals(1L, joinRequestRepository.resolvePending(pending.id, stableId, JoinRequestStatus.ACCEPTED, 9L))
        assertEquals(0L, joinRequestRepository.resolvePending(pending.id, stableId, JoinRequestStatus.DECLINED, 9L))
        assertEquals(JoinRequestStatus.ACCEPTED, joinRequestRepository.findById(pending.id)!!.status)
        assertEquals(listOf(pending.id), joinRequestRepository.findByAccountIdAndStableId(pending.accountId, stableId).map { it.id })
    }

    @Test
    fun `invite codes are unique, stables without one are not limited`() {
        val admin = ObjectId.get()
        val first = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = admin).copy(inviteCode = "ABCDEFGH"))
        val other = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = admin))
        stableRepository.save(testStable(id = ObjectId.get(), adminUserId = admin))

        assertEquals(first.id, stableRepository.findByInviteCodeAndDeletedFalse("ABCDEFGH")!!.id)
        assertTrue(stableRepository.existsByInviteCode("ABCDEFGH"))
        assertFailsWith<DuplicateKeyException> { stableRepository.setInviteCode(other.id, "ABCDEFGH") }

        assertEquals(1L, stableRepository.setInviteCode(first.id, "HGFEDCBA"))
        assertNull(stableRepository.findByInviteCodeAndDeletedFalse("ABCDEFGH"))
        assertFalse(stableRepository.existsByInviteCode("ABCDEFGH"))
        assertEquals("HGFEDCBA", stableRepository.findById(first.id)!!.inviteCode)
    }

    @Test
    fun `name, place and meal times change atomically and keep the invite code`() {
        val admin = ObjectId.get()
        val stable = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = admin).copy(inviteCode = "QRST2345"))

        stableRepository.setNameAndPlace(stable.id, "Neu", "Bregenz", 7L, admin)
        stableRepository.setMealTimes(stable.id, com.lerchenflo.hufly.server.stable.model.MealTimes("05:00", "11:00", "17:00", "21:00"), 8L, admin)

        val stored = stableRepository.findById(stable.id)!!
        assertEquals("Neu", stored.name)
        assertEquals("Bregenz", stored.place)
        assertEquals("05:00", stored.mealTimes.morning)
        assertEquals(8L, stored.updatedAt)
        assertEquals("QRST2345", stored.inviteCode)
        stableRepository.setNameAndPlace(stable.id, "Neu", null, 9L, admin)
        assertNull(stableRepository.findById(stable.id)!!.place)
    }
}
