package com.wmspro.tenant.billing.invoice

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * WmsBillingInvoice — the WMS-side index of an invoice we generated for a
 * customer in a given month. Source of truth for the invoice itself is
 * FreighAi; this document holds:
 *
 *   - The *generation snapshot* — full breakdown of storage / movement /
 *     service lines we computed and submitted, with rates frozen at
 *     submission time so future rate changes never alter the historical bill.
 *   - The *FreighAi binding* — invoiceId, invoiceNo, voucherId, plus the
 *     deterministic referenceNo we use for idempotency on retry.
 *   - A *cached snapshot of FreighAi state* — status, dueDate, outstanding
 *     amount, lastSyncedAt — so the WMS Invoices list can render quickly
 *     without a round-trip per row.
 *
 * Idempotency: a unique compound index on (customerId, billingMonth) means a
 * second `generate` call for the same customer/month silently returns the
 * existing row instead of duplicating. The deterministic `freighaiReferenceNo`
 * adds a second layer — even if the unique index were ever bypassed, FreighAi
 * would surface the existing invoice on the query-before-create check.
 *
 * See WMS-INVOICING-INTEGRATION.md §3.1 and §5 Phase 5.
 */
@Document(collection = "wms_billing_invoice")
@CompoundIndex(name = "customer_month_unique_idx", def = "{'customerId': 1, 'billingMonth': 1}", unique = true)
@CompoundIndex(name = "sync_target_idx", def = "{'status': 1, 'freighaiStatus': 1, 'lastSyncedAt': 1}")
data class WmsBillingInvoice(
    @Id
    val billingInvoiceId: String,

    @Indexed
    val customerId: Long,

    /** ISO yearmonth, e.g. "2026-04". */
    @Indexed
    val billingMonth: String,

    @Indexed
    val status: BillingInvoiceStatus = BillingInvoiceStatus.DRAFT,

    val storageLines: List<StorageLine> = emptyList(),
    val movementLines: List<MovementLine> = emptyList(),
    val serviceLines: List<ServiceLine> = emptyList(),

    val subtotal: BigDecimal = BigDecimal.ZERO,
    val totalVat: BigDecimal = BigDecimal.ZERO,
    val grandTotal: BigDecimal = BigDecimal.ZERO,
    /** When the storage subtotal was below `defaultMonthlyMinimum` and a top-up line was added; otherwise null. */
    val minimumChargeApplied: BigDecimal? = null,

    @Indexed
    val freighaiInvoiceId: String? = null,
    val freighaiInvoiceNo: String? = null,
    val freighaiVoucherId: String? = null,

    /** Deterministic — `"WMS-{customerId}-{billingMonth}"`. Drives idempotency on retry. */
    val freighaiReferenceNo: String,

    /** Cached snapshot of FreighAi's invoice status (DRAFT / SENT / PARTIALLY_PAID / PAID / CANCELLED). */
    val freighaiStatus: String? = null,
    val freighaiInvoiceDate: LocalDate? = null,
    val freighaiDueDate: LocalDate? = null,
    val freighaiOutstandingAmount: BigDecimal? = null,
    val lastSyncedAt: Instant? = null,

    val submissionAttempts: List<SubmissionAttempt> = emptyList(),

    val generatedAt: Instant? = null,
    val generatedBy: String? = null,

    val cancelledAt: Instant? = null,
    val cancelledBy: String? = null,
    val cancelReason: String? = null
)

enum class BillingInvoiceStatus {
    DRAFT,                // pre-create snapshot, before FreighAi POST attempt
    SUBMITTED,            // FreighAi accepted; locks held on linked GRN/GIN/ServiceLog
    SUBMISSION_FAILED,    // FreighAi rejected or unreachable; retryable
    CANCELLED             // FreighAi cancel + lock-clear cascade complete
}

data class StorageLine(
    /** Null = "Unassigned" — storage items with no projectCode. */
    val projectCode: String?,
    val projectLabel: String?,
    val cbmDays: BigDecimal,
    val ratePerDay: BigDecimal,
    val amount: BigDecimal,
    val vatPercent: BigDecimal,
    val vatAmount: BigDecimal,
    val description: String,
    val freighaiChargeTypeId: String,
    /** True for the synthesised top-up line when `defaultMonthlyMinimum` triggered. */
    val isMinimumTopUp: Boolean = false
)

data class MovementLine(
    val direction: MovementDirection,
    val projectCode: String?,
    val projectLabel: String?,
    val totalCbm: BigDecimal,
    val ratePerCbm: BigDecimal,
    val amount: BigDecimal,
    val vatPercent: BigDecimal,
    val vatAmount: BigDecimal,
    val description: String,
    val freighaiChargeTypeId: String,
    /** ReceivingRecord IDs (INBOUND) or fulfillmentIds (OUTBOUND) — for lock cascade + traceback. */
    val sourceRecordIds: List<String> = emptyList()
)

enum class MovementDirection { INBOUND, OUTBOUND }

data class ServiceLine(
    val serviceCode: String,
    val serviceLabel: String,
    val unit: String,
    val quantity: BigDecimal,
    val ratePerUnit: BigDecimal,
    val amount: BigDecimal,
    val vatPercent: BigDecimal,
    val vatAmount: BigDecimal,
    val description: String,
    val freighaiChargeTypeId: String,
    /** ServiceLog IDs included in this line — for lock cascade. */
    val serviceLogIds: List<String> = emptyList()
)

data class SubmissionAttempt(
    val attemptedAt: Instant,
    val success: Boolean,
    val errorMessage: String? = null
)
