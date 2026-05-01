package com.wmspro.tenant.billing.servicelog

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * `POST /api/v1/service-logs` body.
 *
 * Caller supplies: customerId, serviceCode, quantity, performedAt, attachedTo,
 * notes. Backend fills: serviceLogId, performedBy (from `X-User-Email`),
 * loggedAt (`@CreatedDate`), carriedOverFromMonth (computed if the date
 * lands in a billed month — Phase 5 hook).
 */
data class CreateServiceLogRequest(
    @field:NotNull(message = "customerId is required")
    val customerId: Long,

    @field:NotBlank(message = "serviceCode is required")
    val serviceCode: String,

    @field:NotNull(message = "quantity is required")
    @field:PositiveOrZero(message = "quantity must be ≥ 0")
    val quantity: BigDecimal,

    @field:NotNull(message = "performedAt is required")
    val performedAt: LocalDate,

    @field:NotNull(message = "attachedTo is required")
    val attachedTo: AttachedRefRequest,

    @field:Size(max = 1000, message = "notes must be at most 1000 characters")
    val notes: String? = null
)

/** Inline-edit request — same fields as create except `attachedTo` and `customerId` are immutable. */
data class UpdateServiceLogRequest(
    @field:NotBlank(message = "serviceCode is required")
    val serviceCode: String,

    @field:NotNull(message = "quantity is required")
    @field:PositiveOrZero(message = "quantity must be ≥ 0")
    val quantity: BigDecimal,

    @field:NotNull(message = "performedAt is required")
    val performedAt: LocalDate,

    @field:Size(max = 1000, message = "notes must be at most 1000 characters")
    val notes: String? = null
)

data class AttachedRefRequest(
    @field:NotNull(message = "type is required (GRN or GIN)")
    val type: AttachedType,

    @field:NotBlank(message = "attachedTo.id is required")
    val id: String,

    @field:NotBlank(message = "attachedTo.number is required")
    val number: String
)

data class ServiceLogResponse(
    val serviceLogId: String,
    val customerId: Long,
    val serviceCode: String,
    val quantity: BigDecimal,
    val performedAt: LocalDate,
    val attachedTo: AttachedRefResponse,
    val performedBy: String,
    val loggedAt: Instant?,
    val notes: String?,
    val billingInvoiceId: String?,
    val carriedOverFromMonth: String?,
    val updatedAt: Instant?,
    val updatedBy: String?,
    /** Convenience: true when locked to a SUBMITTED billing run. UI uses for affordance gating. */
    val isLocked: Boolean
)

data class AttachedRefResponse(
    val type: AttachedType,
    val id: String,
    val number: String
)
