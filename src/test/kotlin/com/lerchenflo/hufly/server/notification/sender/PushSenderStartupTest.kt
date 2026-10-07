package com.lerchenflo.hufly.server.notification.sender

import com.lerchenflo.hufly.server.notification.model.PushMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PushSenderStartupTest {

    @TempDir
    lateinit var dir: File

    private val message = PushMessage(title = "t", body = "b", data = emptyMap())

    @Test
    fun `broken FCM credentials leave the sender off instead of failing startup`() {
        val file = File(dir, "fcm.json").apply { writeText("not json") }

        val sender = FcmPushSender(file.path)

        assertEquals(PushResult.FAILED, sender.send("token", message))
    }

    @Test
    fun `broken APNs key leaves the sender off instead of failing startup`() {
        val file = File(dir, "key.p8").apply { writeText("not a key") }

        val sender = ApnsPushSender(file.path, "TEAM", "KEY", "app.hufly", false)

        assertEquals(PushResult.FAILED, sender.send("token", message))
    }
}
