package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.LoginRequest
import com.wmspro.tenant.dto.LoginResponse
import com.wmspro.tenant.service.AuthProxyService
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
}
