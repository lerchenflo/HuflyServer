package com.lerchenflo.hufly.server.core.security

class CountingHashEncoder : HashEncoder() {
    var bcryptChecks = 0

    override fun matches(raw: String, hashed: String): Boolean {
        bcryptChecks++
        return super.matches(raw, hashed)
    }
}
