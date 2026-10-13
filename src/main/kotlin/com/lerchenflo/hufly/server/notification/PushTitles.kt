package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.user.model.User
import org.springframework.stereotype.Service

/** Accounts with several stables see which stable a push is about: "<Stallname>: <title>". */
@Service
class PushTitles(private val userRepository: UserRepository, private val stableRepository: StableRepository) {
    fun of(recipient: User, title: String): String {
        if (userRepository.findByAccountIdAndDeletedFalse(recipient.accountId).size < 2) return title
        val stable = stableRepository.findById(recipient.stableId) ?: return title
        return "${stable.name}: $title"
    }
}
