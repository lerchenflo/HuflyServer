package com.lerchenflo.hufly.server.notification.sender

import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform

enum class PushResult { SENT, INVALID_TOKEN, FAILED }

/** One push service (FCM, APNs). Blocking; `PushService` calls it off the request thread. */
interface PlatformPushSender {
    val platform: PushPlatform
    fun send(token: String, message: PushMessage): PushResult
}
