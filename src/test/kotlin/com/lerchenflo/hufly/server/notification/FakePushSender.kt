package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.notification.sender.PlatformPushSender
import com.lerchenflo.hufly.server.notification.sender.PushResult

class FakePushSender(override val platform: PushPlatform) : PlatformPushSender {
    val sent = mutableListOf<Pair<String, PushMessage>>()
    val invalidTokens = mutableSetOf<String>()

    override fun send(token: String, message: PushMessage): PushResult {
        if (token in invalidTokens) return PushResult.INVALID_TOKEN
        sent += token to message
        return PushResult.SENT
    }
}
