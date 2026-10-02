package com.lerchenflo.hufly.server.core.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM for tokens the server must hand out again (refresh replay), so a database leak alone exposes none. */
@Component
class TokenCipher(@Value("\${jwt.secret}") secret: String) {

    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("refresh-token-cipher:$secret".toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    fun decrypt(encrypted: String): String {
        val bytes = Base64.getDecoder().decode(encrypted)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        return String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }
}
