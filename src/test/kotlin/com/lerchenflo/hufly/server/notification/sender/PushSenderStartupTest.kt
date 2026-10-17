package com.lerchenflo.hufly.server.notification.sender

import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.testdata.LogCapture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

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
    fun `FCM logs the project of its service account`() {
        val logs = LogCapture(FcmPushSender::class.java)
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(key.encoded) + "\\n-----END PRIVATE KEY-----\\n"
        val file = File(dir, "firebase-admin.json").apply {
            writeText(
                """{"type":"service_account","project_id":"hufly-29b73","private_key_id":"k1","private_key":"$pem",""" +
                    """"client_email":"firebase-adminsdk@hufly-29b73.iam.gserviceaccount.com","client_id":"1","token_uri":"https://oauth2.googleapis.com/token"}"""
            )
        }

        FcmPushSender(file.path)
        logs.close()

        assertEquals(listOf("INFO FCM loaded (project hufly-29b73)"), logs.lines)
    }

    @Test
    fun `broken APNs key leaves the sender off instead of failing startup`() {
        val file = File(dir, "key.p8").apply { writeText("not a key") }

        val sender = ApnsPushSender(file.path, "TEAM", "KEY", "app.hufly", false)

        assertEquals(PushResult.FAILED, sender.send("token", message))
    }
}
