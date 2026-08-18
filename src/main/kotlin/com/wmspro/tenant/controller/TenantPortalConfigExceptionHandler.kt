package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Makes [TenantPortalConfigController]'s `ResponseStatusException`s answer with the status they name.
 *
 * ## Why this is needed
 *
 * `com.wmspro.common.exception.GlobalExceptionHandler` declares `@ExceptionHandler(Exception::class)`
 * and is component-scanned into this service. A `ResponseStatusException` is an `Exception`, so it
 * was being caught there and rendered as:
 *
 * ```
 * 500  {"success": false, "message": "An unexpected error occurred: 404 NOT_FOUND \"Tenant not found\""}
 * ```
 *
 * Two things wrong with that, both of which defeat the guard's design:
 *
 *  1. **The body states the status it was trying not to reveal.** The 404-instead-of-403 choice
 *     exists so a caller cannot tell "this tenant exists but is not yours" from "no such tenant";
 *     printing `404 NOT_FOUND "Tenant not found"` inside a 500 body gives it away, and an
 *     unauthenticated probe is told `401 UNAUTHORIZED` in plain text.
 *  2. **500-versus-404 is itself the oracle.** A rejected request returned 500 and an accepted one
 *     returned a clean 404, so the status code alone distinguished them — the exact inference the
 *     guard was written to prevent.
 *
 * It also meant every probe wrote a stack trace to stdout, because the shared handler calls
 * `printStackTrace()`.
 *
 * Scoped by `assignableTypes` rather than package, so this changes the behaviour of exactly one
 * controller and cannot alter how any other part of Tenant Service reports errors.
 */
@RestControllerAdvice(assignableTypes = [TenantPortalConfigController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class TenantPortalConfigExceptionHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ApiResponse<Unit>> {
        // The reason is written by this controller, for a client to read — unlike a framework
        // message it contains no internal detail. Logged at DEBUG because a rejection here is an
        // expected outcome, not a fault.
        logger.debug("Portal-config request refused: {}", ex.statusCode)
        return ResponseEntity.status(ex.statusCode)
            .body(ApiResponse.error(ex.reason ?: "Request refused"))
    }
}
