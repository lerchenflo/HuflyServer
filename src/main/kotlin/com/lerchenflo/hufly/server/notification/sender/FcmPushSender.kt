package com.lerchenflo.hufly.server.notification.sender

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.AndroidNotification
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.Notification
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File

/**
 * Android via FCM. Sent as a notification message, not data-only like SchneaggchatV3server, so the system shows it
 * even when the app is not running; high priority on the app's channel [CHANNEL_ID]. Off without a credentials file.
 */
@Component
class FcmPushSender(@Value("\${push.fcm.credentials-file:}") credentialsFile: String) : PlatformPushSender {
    override val platform = PushPlatform.ANDROID
    private val log = LoggerFactory.getLogger(javaClass)

    private val messaging: FirebaseMessaging? = if (credentialsFile.isBlank() || !File(credentialsFile).isFile) {
        log.warn("FCM off: no credentials file (push.fcm.credentials-file)")
        null
    } else {
        val options = File(credentialsFile).inputStream().use { FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(it)).build() }
        val app = FirebaseApp.getApps().firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(options, APP_NAME)
        FirebaseMessaging.getInstance(app)
    }

    override fun send(token: String, message: PushMessage): PushResult {
        val messaging = messaging ?: return PushResult.FAILED
        val fcmMessage = Message.builder()
            .setToken(token)
            .setNotification(Notification.builder().setTitle(message.title).setBody(message.body).build())
            .putAllData(message.data)
            .setAndroidConfig(
                AndroidConfig.builder()
                    .setPriority(AndroidConfig.Priority.HIGH)
                    .setNotification(AndroidNotification.builder().setChannelId(CHANNEL_ID).setSound("default").build())
                    .build()
            )
            .build()
        return try {
            messaging.send(fcmMessage)
            PushResult.SENT
        } catch (e: FirebaseMessagingException) {
            when (e.messagingErrorCode) {
                MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH -> PushResult.INVALID_TOKEN
                else -> PushResult.FAILED.also { log.warn("FCM send failed: {} {}", e.messagingErrorCode, e.message) }
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "hufly_default"
        private const val APP_NAME = "hufly"
    }
}
