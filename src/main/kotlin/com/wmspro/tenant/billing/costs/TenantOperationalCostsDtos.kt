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
    val baseOutboundCostPerCbm: BigDecimal,
    val storageCostTreatment: String = "INTERNAL_STANDARD",
    val inboundCostTreatment: String = "INTERNAL_STANDARD",
    val outboundCostTreatment: String = "INTERNAL_STANDARD"
)

data class TenantOperationalCostsResponse(
    val baseStorageCostPerCbmDay: BigDecimal,
    val baseInboundCostPerCbm: BigDecimal,
    val baseOutboundCostPerCbm: BigDecimal,
    val storageCostTreatment: String,
    val inboundCostTreatment: String,
    val outboundCostTreatment: String,
    val updatedAt: Instant,
    val updatedBy: String,
    val isConfigured: Boolean
)
