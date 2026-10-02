package com.lerchenflo.hufly.server.user.model

/** [generatedPassword] is shown to the admin exactly once; only its hash is stored (USR-2). */
data class CreatedUserResponse(
    val user: UserResponse,
    val generatedPassword: String,
)

data class GeneratedPasswordResponse(
    val generatedPassword: String,
)
