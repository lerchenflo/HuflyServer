package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface RefreshTokenRepository : Repository<RefreshToken, ObjectId> {
    fun save(token: RefreshToken): RefreshToken
    fun findByHashedToken(hashedToken: String): RefreshToken?
    fun findByPreviousHashedToken(previousHashedToken: String): RefreshToken?
    fun deleteByHashedToken(hashedToken: String): Long
    fun deleteByUserId(userId: ObjectId): Long
}
