package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.authentication.model.RefreshToken

class FakeRefreshTokenRepository : RefreshTokenRepository {
    val tokens = mutableListOf<RefreshToken>()

    override fun save(token: RefreshToken): RefreshToken {
        tokens.removeIf { it.id == token.id }
        tokens += token
        return token
    }

    override fun findByHashedToken(hashedToken: String): RefreshToken? =
        tokens.firstOrNull { it.hashedToken == hashedToken }

    override fun findByPreviousHashedToken(previousHashedToken: String): RefreshToken? =
        tokens.firstOrNull { it.previousHashedToken == previousHashedToken }

    override fun deleteByHashedToken(hashedToken: String): Long {
        val before = tokens.size
        tokens.removeIf { it.hashedToken == hashedToken }
        return (before - tokens.size).toLong()
    }

    override fun deleteByUserId(userId: org.bson.types.ObjectId): Long {
        val before = tokens.size
        tokens.removeIf { it.userId == userId }
        return (before - tokens.size).toLong()
    }
}
