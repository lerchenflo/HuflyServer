package com.lerchenflo.hufly.server.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class TokenCipherTest {

    private val cipher = TokenCipher("test-secret-that-is-long-enough-for-hs256-signing")

    @Test
    fun `decrypts what it encrypted`() {
        assertEquals("eyJ.token.value", cipher.decrypt(cipher.encrypt("eyJ.token.value")))
    }

    @Test
    fun `ciphertext hides the token and differs per call`() {
        val first = cipher.encrypt("eyJ.token.value")

        assertFalse(first.contains("token"))
        assertNotEquals(first, cipher.encrypt("eyJ.token.value"))
    }

    @Test
    fun `another secret cannot decrypt`() {
        val other = TokenCipher("another-secret-that-is-long-enough-for-hs256")

        assertFailsWith<Exception> { other.decrypt(cipher.encrypt("eyJ.token.value")) }
    }
}
