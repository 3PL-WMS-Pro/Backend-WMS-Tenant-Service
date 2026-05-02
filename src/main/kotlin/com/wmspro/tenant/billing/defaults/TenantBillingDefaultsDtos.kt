package com.wmspro.tenant.billing.defaults

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.Instant

/**
 * Upsert request — singleton, so create and update share the same shape.
 * The controller maps to the SINGLETON_ID document via the service.
 */
data class UpsertTenantBillingDefaultsRequest(
    @field:NotNull(message = "defaultStorageRatePerCbmDay is required")
    @field:PositiveOrZero(message = "defaultStorageRatePerCbmDay must be ≥ 0")
    val defaultStorageRatePerCbmDay: BigDecimal,

    @field:NotNull(message = "defaultInboundCbmRate is required")
    @field:PositiveOrZero(message = "defaultInboundCbmRate must be ≥ 0")
    val defaultInboundCbmRate: BigDecimal,

    @field:NotNull(message = "defaultOutboundCbmRate is required")
    @field:PositiveOrZero(message = "defaultOutboundCbmRate must be ≥ 0")
    val defaultOutboundCbmRate: BigDecimal,

    @field:PositiveOrZero(message = "defaultMonthlyMinimum must be ≥ 0")
    val defaultMonthlyMinimum: BigDecimal? = null,

    @field:NotBlank(message = "freighaiStorageChargeTypeId is required")
    val freighaiStorageChargeTypeId: String,

    @field:NotBlank(message = "freighaiInboundMovementChargeTypeId is required")
    val freighaiInboundMovementChargeTypeId: String,

    @field:NotBlank(message = "freighaiOutboundMovementChargeTypeId is required")
    val freighaiOutboundMovementChargeTypeId: String
)

data class TenantBillingDefaultsResponse(
    val defaultStorageRatePerCbmDay: BigDecimal,
    val defaultInboundCbmRate: BigDecimal,
    val defaultOutboundCbmRate: BigDecimal,
    val defaultMonthlyMinimum: BigDecimal?,
    val freighaiStorageChargeTypeId: String,
    val freighaiInboundMovementChargeTypeId: String,
    val freighaiOutboundMovementChargeTypeId: String,
    val updatedAt: Instant,
    val updatedBy: String,
    /** Convenience flag — true on first GET when no defaults have been configured yet. */
    val isConfigured: Boolean
)
