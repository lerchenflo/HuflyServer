package com.lerchenflo.hufly.server.user.model

import com.lerchenflo.hufly.server.stable.model.StableResponse
import com.lerchenflo.hufly.server.tag.model.Permission

data class MeResponse(
    val user: UserResponse,
    val isAdmin: Boolean,
    val stable: StableResponse,
    /** Effective permissions: the union of the user's role tags, or all of them for the admin. */
    val permissions: Set<Permission>,
)
