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
// Phase G: idempotency is now per (customer, project, month). When a
// customer has multiple projects, we emit one invoice per project plus
// one "default" invoice for any project-untagged activity (Option 1).
// Old `customer_month_unique_idx` was dropped during the Phase G migration.
@CompoundIndex(name = "customer_project_month_unique_idx", def = "{'customerId': 1, 'projectCode': 1, 'billingMonth': 1}", unique = true)
@CompoundIndex(name = "sync_target_idx", def = "{'status': 1, 'freighaiStatus': 1, 'lastSyncedAt': 1}")
data class WmsBillingInvoice(
    @Id
    val billingInvoiceId: String,

    @Indexed
    val customerId: Long,

    /**
     * Phase G — project bucket this invoice covers. `null` means the
     * "default" bucket (untagged activity, or customers with no projects
     * defined). Together with customerId + billingMonth this uniquely
     * identifies an invoice.
     */
    @Indexed
    val projectCode: String? = null,

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
    val cancelReason: String? = null,

    /**
     * True once an admin has hand-edited this invoice through the WMS edit
     * surface. Flags that `storageLines` / `movementLines` / `serviceLines`
     * are no longer reproducible from warehouse data + rate cards, so
     * reconciliation and any future regeneration must not assume
     * `quantity × rate = amount` for this invoice.
     */
    val manuallyEdited: Boolean = false,

    /**
     * The invoice as it now stands in FreighAi, flat, after the most recent
     * edit. Null until the first edit, at which point this becomes the
     * display truth and the structured storage/movement/service lines above
     * are retained only as provenance — the record of what the engine
     * originally computed.
     */
    val editedLineItems: List<EditedLineItem>? = null,

    /** Append-only audit of every hand-edit. Empty for untouched invoices. */
    val editHistory: List<InvoiceEditEntry> = emptyList()
)

/**
 * A single line as it exists in FreighAi after an edit. Mirrors FreighAi's
 * `InvoiceLineItem` (including its server-assigned `lineNo`) plus two WMS-only
 * markers that never leave this collection.
 */
data class EditedLineItem(
    val lineNo: Int,
    val description: String,
    val quantity: BigDecimal,
    val unit: String,
    val unitPrice: BigDecimal,
    val amount: BigDecimal,
    val chargeTypeId: String?,
    val chargeTypeLabel: String?,
    val vatPercent: BigDecimal?,
    val vatAmount: BigDecimal?,
    val ledgerId: String?,
    /** Added by hand in WMS — no aggregator or source record produced it. */
    val isManual: Boolean = false,
    /**
     * Zeroed out by an admin. Kept here for the audit trail but withheld from
     * the FreighAi payload: FreighAi builds one voucher item per invoice line
     * and rejects any item with neither debit nor credit greater than zero, so
     * a zero line would fail the whole update.
     */
    val isZeroed: Boolean = false
)

/**
 * One hand-edit of an invoice. `reason` is mandatory at the API boundary —
 * an invoice is a financial document and "who changed what" is not enough
 * on its own to reconstruct why months later.
 */
data class InvoiceEditEntry(
    val editedAt: Instant,
    val editedBy: String,
    val reason: String,
    val changes: List<EditedFieldChange>,
    /**
     * FreighAi's status immediately before the edit. "SENT" records that this
     * edit ran a revert → update → re-send cycle rather than a plain update.
     */
    val freighaiStatusBefore: String?,
    val subtotalBefore: BigDecimal?,
    val subtotalAfter: BigDecimal?,
    val grandTotalBefore: BigDecimal?,
    val grandTotalAfter: BigDecimal?
)

data class EditedFieldChange(
    /** Human-readable pointer, e.g. "Line 3 (Storage – April 2026)". */
    val lineRef: String,
    /** One of: description, quantity, unitPrice, vatPercent, added, zeroed. */
    val field: String,
    val oldValue: String?,
    val newValue: String?
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
    val serviceLogIds: List<String> = emptyList(),
    /**
     * Phase G — project bucket this service line belongs to. Null = default
     * bucket. Each per-project invoice contains only its own project's
     * service lines.
     */
    val projectCode: String? = null
)

data class SubmissionAttempt(
    val attemptedAt: Instant,
    val success: Boolean,
    val errorMessage: String? = null
)
