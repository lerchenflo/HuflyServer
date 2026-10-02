package com.lerchenflo.hufly.server.core.security

import java.security.SecureRandom

private val random = SecureRandom()

/** No look-alike characters (0/O, 1/l/I), since admins read these out or copy them by hand. */
private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

fun generatePassword(length: Int = 14): String =
    (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
