package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Manages a tenant's customer-portal code — the slug in `portal.wms.../{CODE}/login`.
 *
 * Mounted under `/api/v1/tenants/...` deliberately: that prefix is already routed at the gateway
 * and is already listed in `TenantInterceptor.CENTRAL_DB_PATHS`, so `tenant_database_mappings`
 * resolves against the central database. A new top-level path would have needed both, and missing
 * the interceptor registration is exactly how per-tenant data was previously corrupted (see the
 * comment in WmsTenantServiceApplication).
 *
 * Tenant Service **owns** this field. wms-customer-portal-service reads it and never writes it.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenant Portal Configuration", description = "Customer-portal enablement per tenant")
class TenantPortalConfigController(
    private val tenantRepository: TenantDatabaseMappingRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Uppercase alphanumeric plus hyphen, 2–32 characters. Mirrors the portal-side validation. */
        private val CODE_PATTERN = Regex("^[A-Z0-9][A-Z0-9-]{1,31}$")
    }

    @GetMapping("/{clientId}/portal-config")
    @Operation(summary = "Read a tenant's customer-portal configuration")
    fun getPortalConfig(@PathVariable clientId: Int): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        return ResponseEntity.ok(
            ApiResponse.success(
                PortalConfigResponse(
                    clientId = tenant.clientId,
                    tenantName = tenant.tenantName,
                    portalTenantCode = tenant.portalTenantCode,
                    portalEnabled = !tenant.portalTenantCode.isNullOrBlank()
                ),
                "Portal configuration retrieved"
            )
        )
    }

    /**
     * Sets the portal code, switching the customer portal on for this tenant.
     *
     * Uniqueness is enforced here rather than only by an index, so the caller gets a usable message
     * instead of a duplicate-key error.
     */
    @PutMapping("/{clientId}/portal-config")
    @Operation(summary = "Set a tenant's customer-portal code")
    fun setPortalConfig(
        @PathVariable clientId: Int,
        @Valid @RequestBody request: SetPortalCodeRequest
    ): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        val code = request.portalTenantCode.trim().uppercase()
        if (!CODE_PATTERN.matches(code)) {
            return ResponseEntity.badRequest().body(
                ApiResponse.error(
                    "Workspace code must be 2–32 characters, uppercase letters, digits or hyphens, " +
                        "and must not start with a hyphen"
                )
            )
        }

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        val clash = tenantRepository.findAll().firstOrNull {
            it.portalTenantCode == code && it.clientId != clientId
        }
        if (clash != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error("That workspace code is already in use")
            )
        }

        val saved = tenantRepository.save(tenant.copy(portalTenantCode = code))
        logger.info("Portal code for tenant {} set to '{}'", clientId, code)

        return ResponseEntity.ok(
            ApiResponse.success(
                PortalConfigResponse(saved.clientId, saved.tenantName, saved.portalTenantCode, true),
                "Customer portal enabled"
            )
        )
    }

    /**
     * Clears the code, disabling the portal for this tenant.
     *
     * Portal user records are deliberately left intact — this is a switch, not a delete, so
     * re-enabling later restores every existing login rather than requiring a re-invite.
     */
    @DeleteMapping("/{clientId}/portal-config")
    @Operation(summary = "Disable the customer portal for a tenant")
    fun disablePortal(@PathVariable clientId: Int): ResponseEntity<ApiResponse<PortalConfigResponse>> {
        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Tenant not found"))

        val saved = tenantRepository.save(tenant.copy(portalTenantCode = null))
        logger.warn("Customer portal DISABLED for tenant {}", clientId)

        return ResponseEntity.ok(
            ApiResponse.success(
                PortalConfigResponse(saved.clientId, saved.tenantName, null, false),
                "Customer portal disabled"
            )
        )
    }
}

data class SetPortalCodeRequest(
    @field:NotBlank(message = "Workspace code is required")
    val portalTenantCode: String
)

data class PortalConfigResponse(
    val clientId: Int,
    val tenantName: String,
    val portalTenantCode: String?,
    val portalEnabled: Boolean
)
