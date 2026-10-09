package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.stable.model.MealTimes
import com.lerchenflo.hufly.server.stable.model.Stable
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository

interface StableRepository : Repository<Stable, ObjectId> {
    fun deleteById(id: ObjectId)
    fun save(stable: Stable): Stable
    fun findById(id: ObjectId): Stable?
    fun findByDeletedFalse(): List<Stable>
    fun findByInviteCodeAndDeletedFalse(inviteCode: String): Stable?
    fun existsByInviteCode(inviteCode: String): Boolean

    /** Atomic, so it never undoes a parallel change of other fields; fails on a duplicate code. */
    @Query("{ '_id': ?0 }")
    @Update("{ '\$set': { 'inviteCode': ?1 } }")
    fun setInviteCode(id: ObjectId, inviteCode: String): Long

    /** Atomic like [setInviteCode], so an edit never brings back a code the admin just replaced. */
    @Query("{ '_id': ?0 }")
    @Update("{ '\$set': { 'name': ?1, 'place': ?2, 'updatedAt': ?3, 'updatedBy': ?4 } }")
    fun setNameAndPlace(id: ObjectId, name: String, place: String?, updatedAt: Long, updatedBy: ObjectId): Long

    @Query("{ '_id': ?0 }")
    @Update("{ '\$set': { 'mealTimes': ?1, 'updatedAt': ?2, 'updatedBy': ?3 } }")
    fun setMealTimes(id: ObjectId, mealTimes: MealTimes, updatedAt: Long, updatedBy: ObjectId): Long
}
