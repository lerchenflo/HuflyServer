package com.lerchenflo.hufly.server.core

import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Parses an id from a path or body; malformed ids answer 400 instead of 500. */
fun parseObjectId(raw: String): ObjectId =
    if (ObjectId.isValid(raw)) ObjectId(raw) else throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id")
