package com.lerchenflo.hufly.server.core.security

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class HashEncoder {

    private val bcrypt = BCryptPasswordEncoder()

    /** Slow salted hash for passwords. */
    fun encode(raw: String): String = bcrypt.encode(raw)!!

    fun matches(raw: String, hashed: String): Boolean = bcrypt.matches(raw, hashed)

    /**
     * Deterministic hash for high-entropy secrets like refresh tokens, so they can be looked up
     * by hash. BCrypt would not work there: it is salted and truncates input after 72 bytes.
     */
    fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHexString()
}
