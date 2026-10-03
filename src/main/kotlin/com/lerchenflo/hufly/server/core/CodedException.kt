package com.lerchenflo.hufly.server.core

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/** An error the client maps to its own message by [code], e.g. `UNKNOWN_PADDOCK`. */
class CodedException(status: HttpStatus, val code: String, message: String) : ResponseStatusException(status, message)

data class CodedErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class CodedExceptionHandler {
    @ExceptionHandler(CodedException::class)
    fun handle(e: CodedException): ResponseEntity<CodedErrorResponse> =
        ResponseEntity.status(e.statusCode).body(CodedErrorResponse(e.code, e.reason ?: e.code))
}
