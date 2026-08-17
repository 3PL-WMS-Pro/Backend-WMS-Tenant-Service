package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.LoginRequest
import com.wmspro.tenant.dto.LoginResponse
import com.wmspro.tenant.dto.RefreshTokenRequest
import com.wmspro.tenant.service.AuthProxyService
import com.wmspro.tenant.service.RefreshOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AuthProxyController — public login endpoint.
 *
 * Mounted at /users/login (not /api/v1/...) to match the path the WMS
 * frontend already calls. The WMS gateway routes /users/[anything] to this
 * service and exempts /users/login from JwtAuthenticationFilter (login is public).
 *
 * The corresponding TenantInterceptor entry adds `/users/login` to its
 * CENTRAL_DB_PATHS list so the request runs against the central database (no
 * X-Client header needed at login time — the user doesn't have a tenant yet).
 */
@RestController
@RequestMapping("/users")
@Tag(name = "Auth Proxy", description = "FreighAi-backed authentication endpoints")
class AuthProxyController(
    private val authProxyService: AuthProxyService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostMapping("/login")
    @Operation(
        summary = "Login (FreighAi-backed)",
        description = "Authenticates against FreighAi and returns a leadtorev-shaped response so the WMS frontend doesn't change. authToken is the FreighAi JWT, used for all subsequent requests."
    )
    fun login(
        @Valid @RequestBody request: LoginRequest
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        logger.info("POST /users/login - email={}", request.email)

        val response = authProxyService.login(request)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                ApiResponse.error("Invalid email or password")
            )

        return ResponseEntity.ok(ApiResponse.success(response, "Login successful"))
    }

    /**
     * Exchanges a refresh token for a fresh access token, returning the same payload
     * shape as `/login` so a client can overwrite its stored session wholesale.
     *
     * Public at the gateway: by definition the caller's access token has expired, so
     * requiring one here would make the endpoint unusable. Authorisation comes from
     * possession of the refresh token itself, which FreighAi validates and revokes.
     */
    @PostMapping("/refresh")
    @Operation(
        summary = "Refresh access token (FreighAi-backed)",
        description = "Exchanges a refresh token for a new access token. FreighAi ROTATES the refresh token, so the returned refreshToken is new and the presented one is now revoked — clients must persist what comes back."
    )
    fun refresh(
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<LoginResponse>> {
        logger.info("POST /users/refresh")

        return when (val outcome = authProxyService.refresh(request.refreshToken)) {
            is RefreshOutcome.Refreshed ->
                ResponseEntity.ok(ApiResponse.success(outcome.response, "Token refreshed"))

            // Terminal: FreighAi answered and refused. Clients end the session.
            is RefreshOutcome.Rejected ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error("Refresh token is invalid or expired")
                )

            // Retryable: we could not reach FreighAi. Deliberately NOT 401 — the
            // mobile client treats 401 as "session over" and force-logs-out, so
            // answering 401 here would end every operator's shift during a blip.
            is RefreshOutcome.Unavailable -> {
                logger.warn("Refresh unavailable: {}", outcome.errorMessage)
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    ApiResponse.error("Authentication service is temporarily unavailable")
                )
            }
        }
    }

    /**
     * Revokes the refresh token at FreighAi.
     *
     * Always answers 200. The client is discarding its credentials regardless, and a
     * token we failed to revoke expires on its own within 7 days — returning an error
     * would only tempt clients to keep a session they have already abandoned.
     */
    @PostMapping("/logout")
    @Operation(
        summary = "Logout (FreighAi-backed)",
        description = "Revokes the refresh token at FreighAi. Always returns 200 — the client clears local credentials either way."
    )
    fun logout(
        @Valid @RequestBody request: RefreshTokenRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        logger.info("POST /users/logout")

        val revoked = authProxyService.logout(request.refreshToken)
        if (!revoked) {
            logger.warn("FreighAi did not confirm refresh-token revocation; client session cleared regardless")
        }

        return ResponseEntity.ok(ApiResponse.success(Unit, "Logged out"))
    }
}
