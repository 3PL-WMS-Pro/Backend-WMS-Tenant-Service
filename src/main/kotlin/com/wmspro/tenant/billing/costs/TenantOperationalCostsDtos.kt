package com.wmspro.tenant.billing.costs

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import java.math.BigDecimal
import java.time.Instant

data class UpsertTenantOperationalCostsRequest(
    @field:NotNull(message = "baseStorageCostPerCbmDay is required")
    @field:PositiveOrZero(message = "baseStorageCostPerCbmDay must be ≥ 0")
    val baseStorageCostPerCbmDay: BigDecimal,

    @field:NotNull(message = "baseInboundCostPerCbm is required")
    @field:PositiveOrZero(message = "baseInboundCostPerCbm must be ≥ 0")
    val baseInboundCostPerCbm: BigDecimal,

    @field:NotNull(message = "baseOutboundCostPerCbm is required")
    @field:PositiveOrZero(message = "baseOutboundCostPerCbm must be ≥ 0")
    val baseOutboundCostPerCbm: BigDecimal
)

data class TenantOperationalCostsResponse(
    val baseStorageCostPerCbmDay: BigDecimal,
    val baseInboundCostPerCbm: BigDecimal,
    val baseOutboundCostPerCbm: BigDecimal,
    val updatedAt: Instant,
    val updatedBy: String,
    val isConfigured: Boolean
)
