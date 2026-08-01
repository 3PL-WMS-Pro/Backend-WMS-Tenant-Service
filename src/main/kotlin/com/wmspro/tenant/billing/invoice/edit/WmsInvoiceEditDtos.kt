package com.wmspro.tenant.billing.invoice.edit

import com.wmspro.tenant.billing.invoice.EditedLineItem
import com.wmspro.tenant.billing.invoice.InvoiceEditEntry
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal

/**
 * Read model for the edit screen: the invoice's lines exactly as FreighAi
 * currently holds them, plus a verdict on whether they can be changed.
 *
 * Lines come from a live FreighAi fetch rather than the WMS cache so an edit
 * made inside FreighAi itself is never silently overwritten by a stale copy.
 */
data class InvoiceEditableView(
    val billingInvoiceId: String,
    val freighaiInvoiceId: String?,
    val freighaiInvoiceNo: String?,
    val freighaiStatus: String?,
    val editable: Boolean,
    /** Why editing is refused. Null when [editable] is true. */
    val blockedReason: String?,
    /**
     * True when saving will run a revert → update → re-send cycle because the
     * invoice has already gone to the customer. The UI warns before saving.
     */
    val requiresRevert: Boolean,
    val lines: List<EditableLine>,
    val subtotal: BigDecimal?,
    val totalVat: BigDecimal?,
    val grandTotal: BigDecimal?,
    val manuallyEdited: Boolean,
    val editHistory: List<InvoiceEditEntry>,
    /**
     * Opaque snapshot token. The client echoes it back on save; if the invoice
     * moved in the meantime — another admin, or an edit made inside FreighAi —
     * the save is refused rather than silently overwriting someone else's work
     * on a financial document.
     */
    val baseVersion: String
)

data class EditableLine(
    /** FreighAi's line number. Identifies this line across an edit. */
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
    val isManual: Boolean,
    val isZeroed: Boolean
)

/**
 * Edit request.
 *
 * [lines] is the complete intended line list. Existing lines carry their
 * [EditLineRequest.lineNo]; newly added lines leave it null. Removal is not
 * supported — every existing `lineNo` must still be present, or the request is
 * rejected. To drop a charge, zero its unit price: the line stays on the WMS
 * record for audit and is withheld from the FreighAi payload.
 */
data class EditInvoiceLinesRequest(
    @field:NotEmpty(message = "At least one line is required")
    @field:Valid
    val lines: List<EditLineRequest>,

    @field:NotBlank(message = "A reason for the edit is required")
    @field:Size(max = 500, message = "Reason must be at most 500 characters")
    val reason: String,

    /** The `baseVersion` from the [InvoiceEditableView] this edit started from. */
    @field:NotBlank(message = "baseVersion is required — reload the invoice before saving")
    val baseVersion: String
)

data class EditLineRequest(
    /** Null for a newly added line; FreighAi's line number for an existing one. */
    val lineNo: Int? = null,

    @field:NotBlank(message = "Description is required")
    @field:Size(max = 500, message = "Description must be at most 500 characters")
    val description: String,

    @field:PositiveOrZero(message = "Quantity cannot be negative")
    val quantity: BigDecimal,

    @field:NotBlank(message = "Unit is required")
    @field:Size(max = 32, message = "Unit must be at most 32 characters")
    val unit: String,

    @field:PositiveOrZero(message = "Unit price cannot be negative")
    val unitPrice: BigDecimal,

    /**
     * Required for added lines — it drives VAT and ledger routing on the
     * FreighAi side. Existing lines echo back whatever they already carry.
     */
    val chargeTypeId: String? = null,
    val chargeTypeLabel: String? = null,
    val vatPercent: BigDecimal? = null,
    val ledgerId: String? = null
)

/** Returned after a successful edit. */
data class InvoiceEditResult(
    val billingInvoiceId: String,
    val freighaiInvoiceId: String?,
    val freighaiInvoiceNo: String?,
    val freighaiStatus: String?,
    val subtotal: BigDecimal,
    val totalVat: BigDecimal,
    val grandTotal: BigDecimal,
    val lines: List<EditedLineItem>,
    /** True when the invoice was pulled back from SENT and successfully re-sent. */
    val revertCycleRan: Boolean,
    /** Lines withheld from FreighAi because they were zeroed. */
    val zeroedLineCount: Int,
    /**
     * Set when the edit succeeded but left something needing attention — most
     * importantly a re-send that failed, which leaves a previously-issued
     * invoice sitting in draft where the customer cannot see it.
     */
    val warning: String? = null
)

/**
 * Raised when an edit cannot proceed. Carries the reason verbatim — usually
 * FreighAi's own message (closed fiscal period, status conflict, payment
 * already allocated) — so the admin sees something actionable.
 */
class InvoiceEditConflictException(message: String) : RuntimeException(message)
