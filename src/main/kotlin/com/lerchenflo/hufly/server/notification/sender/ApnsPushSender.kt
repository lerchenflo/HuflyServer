package com.lerchenflo.hufly.server.notification.sender

import com.eatthepath.pushy.apns.ApnsClient
import com.eatthepath.pushy.apns.ApnsClientBuilder
import com.eatthepath.pushy.apns.DeliveryPriority
import com.eatthepath.pushy.apns.PushType
import com.eatthepath.pushy.apns.auth.ApnsSigningKey
import com.eatthepath.pushy.apns.util.SimpleApnsPayloadBuilder
import com.eatthepath.pushy.apns.util.SimpleApnsPushNotification
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * iOS via APNs. A plain alert like WhatsApp's: no `interruption-level` (so "active"; SchneaggchatV3server's
 * "time-sensitive" made iOS label its pushes), priority 10, kept by APNs for a week while the phone is offline.
 * Off unless key file, team id, key id and bundle id are set.
 */
@Component
class ApnsPushSender(
    @Value("\${push.apns.key-file:}") keyFile: String,
    @Value("\${push.apns.team-id:}") teamId: String,
    @Value("\${push.apns.key-id:}") keyId: String,
    @Value("\${push.apns.bundle-id:}") private val bundleId: String,
    @Value("\${push.apns.production:false}") production: Boolean,
) : PlatformPushSender {
    override val platform = PushPlatform.IOS
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: ApnsClient? = if (listOf(keyFile, teamId, keyId, bundleId).any { it.isBlank() } || !File(keyFile).isFile) {
        log.warn("APNs off: push.apns.key-file, team-id, key-id and bundle-id are needed")
        null
    } else {
        ApnsClientBuilder()
            .setApnsServer(if (production) ApnsClientBuilder.PRODUCTION_APNS_HOST else ApnsClientBuilder.DEVELOPMENT_APNS_HOST)
            .setSigningKey(ApnsSigningKey.loadFromPkcs8File(File(keyFile), teamId, keyId))
            .build()
    }

    override fun send(token: String, message: PushMessage): PushResult {
        val client = client ?: return PushResult.FAILED
        val payload = SimpleApnsPayloadBuilder()
            .setAlertTitle(message.title)
            .setAlertBody(message.body)
            .setSound(SimpleApnsPayloadBuilder.DEFAULT_SOUND_FILENAME)
            .apply { message.data.forEach { (key, value) -> addCustomProperty(key, value) } }
            .build()
        val notification = SimpleApnsPushNotification(
            token, bundleId, payload, Instant.now().plus(KEEP_WHILE_OFFLINE), DeliveryPriority.IMMEDIATE, PushType.ALERT,
        )
        return try {
            val response = client.sendNotification(notification).get()
            when {
                response.isAccepted -> PushResult.SENT
                response.rejectionReason.orElse("") in INVALID_TOKEN_REASONS -> PushResult.INVALID_TOKEN
                else -> PushResult.FAILED.also { log.warn("APNs rejected: {}", response.rejectionReason.orElse("")) }
            }
        } catch (e: Exception) {
            log.warn("APNs send failed: {}", e.message)
            PushResult.FAILED
        }
    }

    @PreDestroy
    fun close() {
        client?.close()
    }

    companion object {
        private val KEEP_WHILE_OFFLINE = Duration.ofDays(7)
        private val INVALID_TOKEN_REASONS = setOf("BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic", "ExpiredToken")
    }
}
