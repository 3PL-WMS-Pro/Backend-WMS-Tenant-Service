package com.wmspro.tenant.billing.adjustment

import com.wmspro.tenant.billing.invoice.MovementDirection
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

/**
 * `POST /api/v1/movement-cost-adjustments` body.
 *
 * `ratePerUnitDelta` is signed: positive for surcharges (forklift,
 * overtime), negative for credits. No `@PositiveOrZero` constraint —
 * negative values are valid V1 input.
 */
data class CreateMovementCostAdjustmentRequest(
    @field:NotNull(message = "customerId is required")
    val customerId: Long,

    @field:NotNull(message = "attachedTo is required")
    val attachedTo: AdjustmentAttachedRefRequest,

    @field:NotNull(message = "direction is required (INBOUND or OUTBOUND)")
    val direction: MovementDirection,

    @field:NotBlank(message = "reason is required")
    @field:Size(max = 64, message = "reason must be at most 64 characters")
    val reason: String,

    @field:NotNull(message = "ratePerUnitDelta is required")
    val ratePerUnitDelta: BigDecimal,

    @field:Size(max = 1000, message = "notes must be at most 1000 characters")
    val notes: String? = null
)

/** Inline-edit request — `attachedTo`, `customerId`, `direction` immutable. */
data class UpdateMovementCostAdjustmentRequest(
    @field:NotBlank(message = "reason is required")
    @field:Size(max = 64)
    val reason: String,

    @field:NotNull(message = "ratePerUnitDelta is required")
    val ratePerUnitDelta: BigDecimal,

    @field:Size(max = 1000, message = "notes must be at most 1000 characters")
    val notes: String? = null
)

data class AdjustmentAttachedRefRequest(
    @field:NotNull(message = "type is required (GRN or GIN)")
    val type: AdjustmentAttachedType,

    @field:NotBlank(message = "attachedTo.id is required")
    val id: String,

    @field:NotBlank(message = "attachedTo.number is required")
    val number: String
)

data class MovementCostAdjustmentResponse(
    val adjustmentId: String,
    val customerId: Long,
    val attachedTo: AdjustmentAttachedRefResponse,
    val direction: MovementDirection,
    val reason: String,
    val ratePerUnitDelta: BigDecimal,
    val notes: String?,
    val createdBy: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val updatedBy: String?,
    val billingInvoiceId: String?,
    /** Convenience: true when locked to a SUBMITTED billing run. UI uses for affordance gating. */
    val isLocked: Boolean
)

data class AdjustmentAttachedRefResponse(
    val type: AdjustmentAttachedType,
    val id: String,
    val number: String
)
