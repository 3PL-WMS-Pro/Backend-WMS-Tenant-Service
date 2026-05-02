package com.wmspro.tenant.billing.defaults

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.Instant

/**
 * `/api/v1/billing-defaults/tenant` — singleton GET/PUT for tenant-wide
 * billing defaults (rates and FreighAi ChargeType bindings).
 *
 * GET returns either the configured defaults, or a placeholder response
 * with `isConfigured=false` when nothing has been set yet — letting the
 * frontend show an empty form on first visit.
 */
@RestController
@RequestMapping("/api/v1/billing-defaults")
class TenantBillingDefaultsController(
    private val service: TenantBillingDefaultsService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/tenant")
    fun get(): ResponseEntity<ApiResponse<TenantBillingDefaultsResponse>> {
        val entity = service.findOrNull()
        val response = if (entity != null) {
            entity.toResponse(isConfigured = true)
        } else {
            unconfiguredPlaceholder()
        }
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant billing defaults retrieved"))
    }

    @PutMapping("/tenant")
    fun upsert(
        @Valid @RequestBody request: UpsertTenantBillingDefaultsRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<TenantBillingDefaultsResponse>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val saved = service.upsert(request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(isConfigured = true), "Tenant billing defaults saved"))
        } catch (e: IllegalArgumentException) {
            logger.warn("Validation error upserting tenant billing defaults: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: Exception) {
            logger.error("Error upserting tenant billing defaults", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    private fun TenantBillingDefaults.toResponse(isConfigured: Boolean) = TenantBillingDefaultsResponse(
        defaultStorageRatePerCbmDay = defaultStorageRatePerCbmDay,
        defaultInboundCbmRate = defaultInboundCbmRate,
        defaultOutboundCbmRate = defaultOutboundCbmRate,
        defaultMonthlyMinimum = defaultMonthlyMinimum,
        freighaiStorageChargeTypeId = freighaiStorageChargeTypeId,
        freighaiInboundMovementChargeTypeId = freighaiInboundMovementChargeTypeId,
        freighaiOutboundMovementChargeTypeId = freighaiOutboundMovementChargeTypeId,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        isConfigured = isConfigured
    )

    private fun unconfiguredPlaceholder() = TenantBillingDefaultsResponse(
        defaultStorageRatePerCbmDay = BigDecimal.ZERO,
        defaultInboundCbmRate = BigDecimal.ZERO,
        defaultOutboundCbmRate = BigDecimal.ZERO,
        defaultMonthlyMinimum = null,
        freighaiStorageChargeTypeId = "",
        freighaiInboundMovementChargeTypeId = "",
        freighaiOutboundMovementChargeTypeId = "",
        updatedAt = Instant.EPOCH,
        updatedBy = "",
        isConfigured = false
    )
}
