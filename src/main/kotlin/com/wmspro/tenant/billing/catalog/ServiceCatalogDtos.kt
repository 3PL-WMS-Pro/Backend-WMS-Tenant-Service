package com.wmspro.tenant.billing.catalog

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

/**
 * Request body for `POST /api/v1/service-catalog`.
 *
 * `serviceCode` is enforced uppercase-snake (`^[A-Z][A-Z0-9_]*$`) so callers
 * cannot drift the canonical form. The same field is the document `_id` on
 * the model so case-insensitive duplicates would silently collide; the
 * pattern keeps that from happening.
 */
data class CreateServiceCatalogRequest(
    @field:NotBlank(message = "serviceCode is required")
    @field:Pattern(
        regexp = "^[A-Z][A-Z0-9_]*$",
        message = "serviceCode must be uppercase letters/digits/underscores starting with a letter (e.g. 'PALLETIZATION')"
    )
    @field:Size(max = 64, message = "serviceCode must be at most 64 characters")
    val serviceCode: String,

    @field:NotBlank(message = "label is required")
    @field:Size(max = 200, message = "label must be at most 200 characters")
    val label: String,

    @field:NotBlank(message = "unit is required")
    @field:Size(max = 32, message = "unit must be at most 32 characters")
    val unit: String,

    @field:PositiveOrZero(message = "standardRatePerUnit must be ≥ 0")
    val standardRatePerUnit: BigDecimal,

    /** Phase B: optional internal cost per unit. Null = not captured yet. */
    @field:PositiveOrZero(message = "standardCostPerUnit must be ≥ 0")
    val standardCostPerUnit: BigDecimal? = null,

    @field:NotBlank(message = "freighaiChargeTypeId is required")
    val freighaiChargeTypeId: String,

    /** Optional override for the FreighAi ChargeType's vatPercent. Null = inherit. */
    val vatPercent: BigDecimal? = null,

    val isActive: Boolean = true
)

/**
 * Request body for `PUT /api/v1/service-catalog/{serviceCode}`.
 *
 * `serviceCode` cannot be changed (it's the document `_id`). All other fields
 * are full-replacement.
 */
data class UpdateServiceCatalogRequest(
    @field:NotBlank(message = "label is required")
    @field:Size(max = 200, message = "label must be at most 200 characters")
    val label: String,

    @field:NotBlank(message = "unit is required")
    @field:Size(max = 32, message = "unit must be at most 32 characters")
    val unit: String,

    @field:PositiveOrZero(message = "standardRatePerUnit must be ≥ 0")
    val standardRatePerUnit: BigDecimal,

    /** Phase B: optional internal cost per unit. Null = not captured yet. */
    @field:PositiveOrZero(message = "standardCostPerUnit must be ≥ 0")
    val standardCostPerUnit: BigDecimal? = null,

    @field:NotBlank(message = "freighaiChargeTypeId is required")
    val freighaiChargeTypeId: String,

    val vatPercent: BigDecimal? = null,

    val isActive: Boolean = true
)

data class ServiceCatalogResponse(
    val serviceCode: String,
    val label: String,
    val unit: String,
    val standardRatePerUnit: BigDecimal,
    /** Phase B: optional internal cost per unit. */
    val standardCostPerUnit: BigDecimal?,
    val freighaiChargeTypeId: String,
    val vatPercent: BigDecimal?,
    val isActive: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val createdBy: String?,
    val updatedBy: String?
)
