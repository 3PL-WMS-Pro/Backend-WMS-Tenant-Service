package com.wmspro.tenant.billing.costs

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
 * `/api/v1/operational-costs/tenant` — singleton GET/PUT for tenant-wide
 * internal cost rates.
 *
 * Internal-only data — never exposed on the customer-facing invoice or
 * to FreighAi. Used by billing-run snapshot writes (Phase B+C) and
 * reconciliation reports (Phase D).
 */
@RestController
@RequestMapping("/api/v1/operational-costs")
class TenantOperationalCostsController(
    private val service: TenantOperationalCostsService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/tenant")
    fun get(): ResponseEntity<ApiResponse<TenantOperationalCostsResponse>> {
        val entity = service.findOrNull()
        val response = if (entity != null) {
            entity.toResponse(isConfigured = true)
        } else {
            unconfiguredPlaceholder()
        }
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant operational costs retrieved"))
    }

    @PutMapping("/tenant")
    fun upsert(
        @Valid @RequestBody request: UpsertTenantOperationalCostsRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<TenantOperationalCostsResponse>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val saved = service.upsert(request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(isConfigured = true), "Tenant operational costs saved"))
        } catch (e: IllegalArgumentException) {
            logger.warn("Validation error upserting tenant operational costs: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: Exception) {
            logger.error("Error upserting tenant operational costs", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    private fun TenantOperationalCosts.toResponse(isConfigured: Boolean) = TenantOperationalCostsResponse(
        baseStorageCostPerCbmDay = baseStorageCostPerCbmDay,
        baseInboundCostPerCbm = baseInboundCostPerCbm,
        baseOutboundCostPerCbm = baseOutboundCostPerCbm,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        isConfigured = isConfigured
    )

    private fun unconfiguredPlaceholder() = TenantOperationalCostsResponse(
        baseStorageCostPerCbmDay = BigDecimal.ZERO,
        baseInboundCostPerCbm = BigDecimal.ZERO,
        baseOutboundCostPerCbm = BigDecimal.ZERO,
        updatedAt = Instant.EPOCH,
        updatedBy = "",
        isConfigured = false
    )
}
