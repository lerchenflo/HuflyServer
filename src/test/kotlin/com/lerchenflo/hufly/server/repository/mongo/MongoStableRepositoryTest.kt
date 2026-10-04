package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.stable.model.MealTimes
import com.lerchenflo.hufly.server.testdata.testStable
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoStableRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var mongoTemplate: MongoTemplate
    @Autowired lateinit var stableRepository: StableRepository

    @Test
    fun `a stable saved before meal times existed reads the default times`() {
        val id = ObjectId.get()
        mongoTemplate.getCollection("stables").insertOne(
            Document(mapOf("_id" to id, "name" to "Alt", "adminUserId" to ObjectId.get(), "subscriptionStatus" to "TRIAL",
                "subscriptionValidUntil" to null, "createdAt" to 0L, "updatedAt" to 0L, "updatedBy" to ObjectId.get(), "deleted" to false))
        )

        assertEquals(MealTimes(), stableRepository.findById(id)?.mealTimes)
    }

    @Test
    fun `meal times survive a round trip`() {
        val times = MealTimes("05:30", "11:00", "17:00", "21:30")
        val stable = stableRepository.save(testStable(id = ObjectId.get(), adminUserId = ObjectId.get()).copy(mealTimes = times))

        assertEquals(times, stableRepository.findById(stable.id)?.mealTimes)
    }
}
