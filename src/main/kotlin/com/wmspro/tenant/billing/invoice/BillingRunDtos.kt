package com.wmspro.tenant.billing.invoice

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * `POST /api/v1/billing-runs/preview` body — runs the aggregators without
 * any persistence. Used by Phase 6's pre-billing-preview UI.
 */
data class BillingPreviewRequest(
    @field:NotNull val customerId: Long,
    @field:NotBlank
    @field:Pattern(regexp = "^\\d{4}-\\d{2}$", message = "billingMonth must be YYYY-MM")
    val billingMonth: String
)

/**
 * `POST /api/v1/billing-runs/generate` — same shape as preview. Triggers
 * the full flow: aggregate → snapshot → POST to FreighAi → cascade locks.
 */
data class GenerateBillingRunRequest(
    @field:NotNull val customerId: Long,
    @field:NotBlank
    @field:Pattern(regexp = "^\\d{4}-\\d{2}$", message = "billingMonth must be YYYY-MM")
    val billingMonth: String
)

/**
 * `POST /api/v1/billing-runs/generate-all` — sweep all
 * billingEnabled customers for a single month. Cron (Phase 10) hits this
 * with `billingMonth = previous_month`. Admin can also trigger it.
 */
data class GenerateAllBillingRunsRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^\\d{4}-\\d{2}$", message = "billingMonth must be YYYY-MM")
    val billingMonth: String,
    /** Optional subset; null = all `billingEnabled` customers. */
    val customerIds: List<Long>? = null
)

data class CancelBillingInvoiceRequest(
    @field:NotBlank val reason: String
)

// ──────────────────────────────────────────────────────────────────────
// Responses
// ──────────────────────────────────────────────────────────────────────

/**
 * Preview response — same line shapes as the persisted invoice plus a
 * data-quality block (BLOCKER vs WARNING). Phase 6 uses this DTO directly
 * to render the modal.
 */
data class BillingPreviewResponse(
    val customerId: Long,
    val billingMonth: String,
    val storageLines: List<StorageLine>,
    val movementLines: List<MovementLine>,
    val serviceLines: List<ServiceLine>,
    val subtotal: BigDecimal,
    val totalVat: BigDecimal,
    val grandTotal: BigDecimal,
    val minimumChargeApplied: BigDecimal?,
    val dataQualityWarnings: List<DataQualityWarning>,
    val canGenerate: Boolean,
    /** True if a SUBMITTED invoice already exists; generate would no-op. */
    val alreadyGenerated: Boolean,
    val existingInvoiceId: String? = null
)

data class DataQualityWarning(
    val severity: WarningSeverity,
    val code: String,
    val message: String,
    val affectedIds: List<String> = emptyList()
)

enum class WarningSeverity { BLOCKER, WARNING }

data class WmsBillingInvoiceResponse(
    val billingInvoiceId: String,
    val customerId: Long,
    val billingMonth: String,
    val status: BillingInvoiceStatus,
    val storageLines: List<StorageLine>,
    val movementLines: List<MovementLine>,
    val serviceLines: List<ServiceLine>,
    val subtotal: BigDecimal,
    val totalVat: BigDecimal,
    val grandTotal: BigDecimal,
    val minimumChargeApplied: BigDecimal?,
    val freighaiInvoiceId: String?,
    val freighaiInvoiceNo: String?,
    val freighaiVoucherId: String?,
    val freighaiReferenceNo: String,
    val freighaiStatus: String?,
    val freighaiInvoiceDate: LocalDate?,
    val freighaiDueDate: LocalDate?,
    val freighaiOutstandingAmount: BigDecimal?,
    val lastSyncedAt: Instant?,
    val submissionAttempts: List<SubmissionAttempt>,
    val generatedAt: Instant?,
    val generatedBy: String?,
    val cancelledAt: Instant?,
    val cancelledBy: String?,
    val cancelReason: String?
)

data class GenerateAllBillingRunsResponse(
    val billingMonth: String,
    val triggeredAt: Instant,
    val triggeredBy: String,
    val succeeded: List<Long>,
    val skipped: List<SkipDetail>,
    val failed: List<FailDetail>
)

data class SkipDetail(val customerId: Long, val reason: String)
data class FailDetail(val customerId: Long, val errorMessage: String)
