package com.lerchenflo.hufly.server.stable

import java.security.SecureRandom

/** Invite codes: 8 characters without look-alikes (no 0/O/1/I), shown as `XXXX-XXXX`, stored without the dash. */
object InviteCodes {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 8

    private val random = SecureRandom()

    fun generate(): String = buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /** Case-insensitive, ignores dashes and whitespace; null if the result cannot be a code. */
    fun normalize(input: String): String? =
        input.filterNot { it == '-' || it.isWhitespace() }.uppercase().takeIf { code -> code.length == LENGTH && code.all { it in ALPHABET } }
}
