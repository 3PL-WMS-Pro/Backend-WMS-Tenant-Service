package com.wmspro.tenant.billing.invoice.edit

import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.common.external.freighai.client.InvoiceUpdateResult
import com.wmspro.common.external.freighai.client.InvoiceAllocationLookupResult
import com.wmspro.common.external.freighai.client.InvoiceAllocationMutationResult
import com.wmspro.common.external.freighai.client.RevertResult
import com.wmspro.common.external.freighai.client.SendResult
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItem
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItemResponse
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceResponse
import com.wmspro.common.external.freighai.dto.UpdateFreighAiInvoiceRequest
import com.wmspro.common.external.freighai.dto.FreighAiJobAllocationItem
import com.wmspro.common.external.freighai.dto.FreighAiJobAllocationResponse
import com.wmspro.common.external.freighai.dto.ReplaceFreighAiJobAllocationsRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.EditedFieldChange
import com.wmspro.tenant.billing.invoice.EditedLineItem
import com.wmspro.tenant.billing.invoice.InvoiceEditEntry
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.invoice.deriveWarehouseJobLifecycle
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * WmsInvoiceEditService — hand-editing of a generated invoice from inside WMS.
 *
 * ## Why this is an edit-through, not a local editor
 *
 * FreighAi owns the invoice. It recomputes line amounts, VAT and totals from
 * the lines it is given, and rebuilds the paired accounting voucher in the
 * same transaction. Reimplementing any of that on the WMS side would mean two
 * sources of truth for a financial document, so this service does none of it:
 * it validates the admin's intent, pushes the full line list to FreighAi, and
 * stores whatever comes back.
 *
 * ## Flow
 *
 * ```
 *   FreighAi DRAFT →  PUT /invoices/{id}                       → save
 *   FreighAi SENT  →  revert-to-draft → PUT → send             → save
 *   anything else  →  refused, with the reason
 * ```
 *
 * The revert path is protected on the FreighAi side by the voucher's payment
 * allocation guard, so a part-paid invoice can never be rewritten even if the
 * status checks here were bypassed.
 *
 * ## Two rules that are easy to get wrong
 *
 * 1. **No line removal.** Every line currently on the invoice must still be
 *    present in the request. Deleting a movement line would orphan its GRNs /
 *    GINs: they stay stamped with `billingInvoiceId`, and the aggregators only
 *    pick up records where that field is null — so the activity would never be
 *    billed again, silently. Zeroing is offered instead, which keeps the
 *    source records locked and the money accounted for.
 *
 * 2. **Zeroed lines are withheld from FreighAi.** FreighAi emits one voucher
 *    item per invoice line and `VoucherService.validateItemStructure` rejects
 *    any item with neither debit nor credit greater than zero — a single zero
 *    line fails the *entire* update. Zeroed lines are therefore kept on the
 *    WMS record for audit and filtered out of the payload, mirroring the
 *    existing Phase H.2 flat-fee reshaping at the same boundary.
 */
@Service
class WmsInvoiceEditService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val freighAiInvoiceClient: FreighAiInvoiceClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** FreighAi statuses whose lines can still be changed. */
        val EDITABLE_DIRECT = setOf("DRAFT")
        val EDITABLE_VIA_REVERT = setOf("SENT")
        val WAREHOUSE_ACCOUNTING_CATEGORIES = setOf(
            "WAREHOUSE_STORAGE",
            "WAREHOUSE_INBOUND",
            "WAREHOUSE_OUTBOUND",
            "WAREHOUSE_SERVICE"
        )
        val WAREHOUSE_SELLING_LINE_ID = Regex("^[0-9a-f]{32}$")
    }

    // ──────────────────────────────────────────────────────────────────
    // Read
    // ──────────────────────────────────────────────────────────────────

    /**
     * Current lines plus an editability verdict.
     *
     * Lines are read live from FreighAi, not from the WMS cache, so an edit
     * made directly in FreighAi is reflected here rather than being clobbered
     * by a stale snapshot on the next save.
     */
    fun getEditableView(billingInvoiceId: String, authToken: String): InvoiceEditableView {
        val invoice = loadInvoice(billingInvoiceId)

        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: return notEditable(invoice, null, "This invoice has no FreighAi binding yet — nothing to edit.")

        if (invoice.status != BillingInvoiceStatus.SUBMITTED) {
            return notEditable(
                invoice, null,
                "Invoice is ${invoice.status} in WMS. Only SUBMITTED invoices can be edited."
            )
        }

        val freighai = freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken)
            ?: return notEditable(invoice, null, "FreighAi is unreachable — try again in a moment.")

        val status = freighai.currentStatus?.uppercase()
        val lines = mergeLines(freighai, invoice)

        val blockedReason = when {
            status == null -> "FreighAi has not reported a status for this invoice yet."
            status in EDITABLE_DIRECT || status in EDITABLE_VIA_REVERT -> null
            status == "CANCELLED" -> "This invoice is cancelled in FreighAi and can no longer be edited."
            status == "PAID" || status == "PARTIALLY_PAID" ->
                "This invoice has payments against it. Raise a credit note in FreighAi instead of editing it."
            else -> "FreighAi reports status $status, which cannot be edited."
        }

        return InvoiceEditableView(
            billingInvoiceId = invoice.billingInvoiceId,
            freighaiInvoiceId = freighaiInvoiceId,
            freighaiInvoiceNo = freighai.invoiceNo,
            freighaiStatus = freighai.currentStatus,
            editable = blockedReason == null,
            blockedReason = blockedReason,
            requiresRevert = status in EDITABLE_VIA_REVERT,
            lines = lines,
            subtotal = freighai.subtotal,
            totalVat = freighai.totalVatAmount,
            grandTotal = freighai.grandTotal,
            manuallyEdited = invoice.manuallyEdited,
            editHistory = invoice.editHistory,
            warehouseJobManaged = invoice.generationContractVersion == WarehouseJobGenerationContracts.V1,
            baseVersion = versionOf(freighai)
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Write
    // ──────────────────────────────────────────────────────────────────

    /**
     * Apply an edit. Not `@Transactional` on purpose: the work spans an
     * external system, so a Mongo transaction would give a false sense of
     * atomicity. Consistency comes from ordering and from the compensating
     * re-send below.
     */
    fun applyEdit(
        billingInvoiceId: String,
        request: EditInvoiceLinesRequest,
        userEmail: String,
        authToken: String
    ): InvoiceEditResult {
        val reason = request.reason.trim()
        if (reason.isEmpty()) throw InvoiceEditConflictException("A reason for the edit is required.")

        val invoice = loadInvoice(billingInvoiceId)
        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: throw InvoiceEditConflictException("This invoice has no FreighAi binding yet — nothing to edit.")
        if (invoice.status != BillingInvoiceStatus.SUBMITTED) {
            throw InvoiceEditConflictException(
                "Invoice is ${invoice.status} in WMS. Only SUBMITTED invoices can be edited."
            )
        }

        // Re-fetch rather than trusting the client's snapshot: this is both the
        // authoritative "before" for the diff and the concurrency check.
        val before = freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken)
            ?: throw InvoiceEditConflictException("FreighAi is unreachable — try again in a moment.")

        val statusBefore = before.currentStatus?.uppercase()
        val needsRevert = when {
            statusBefore in EDITABLE_DIRECT -> false
            statusBefore in EDITABLE_VIA_REVERT -> true
            statusBefore == "PAID" || statusBefore == "PARTIALLY_PAID" -> throw InvoiceEditConflictException(
                "This invoice has payments against it. Raise a credit note in FreighAi instead of editing it."
            )
            statusBefore == "CANCELLED" -> throw InvoiceEditConflictException(
                "This invoice is cancelled in FreighAi and can no longer be edited."
            )
            else -> throw InvoiceEditConflictException(
                "FreighAi reports status ${before.currentStatus ?: "unknown"}, which cannot be edited."
            )
        }

        if (versionOf(before) != request.baseVersion) {
            throw InvoiceEditConflictException(
                "This invoice changed since you opened it — reload and reapply your changes."
            )
        }

        val currentLines = mergeLines(before, invoice)
        // Run the public line-number contract first so duplicate/missing/stale
        // requests receive the established, actionable error messages.
        validateNoRemovals(currentLines, request.lines)
        val submittedLines = prepareSubmittedLines(invoice, currentLines, request)

        val payloadLines = submittedLines.filter { amountOf(it).signum() > 0 }
        if (payloadLines.isEmpty()) {
            throw InvoiceEditConflictException(
                "Every line is zero — an invoice must keep at least one billable line. " +
                    "Cancel the invoice instead if nothing should be charged."
            )
        }
        payloadLines.firstOrNull { it.chargeTypeId.isNullOrBlank() }?.let {
            throw InvoiceEditConflictException(
                "Line \"${it.description}\" has no charge type. Every billable line needs one — " +
                    "it drives VAT and ledger routing in FreighAi."
            )
        }

        // ── Pull back to DRAFT if the invoice has already gone out ──────
        if (needsRevert) {
            when (val revert = freighAiInvoiceClient.revertToDraft(
                freighaiInvoiceId, "Reverted for WMS edit: $reason", authToken
            )) {
                is RevertResult.Failure -> throw InvoiceEditConflictException(
                    "Could not pull the invoice back to draft: ${revert.errorMessage}"
                )
                RevertResult.Success -> logger.info(
                    "Reverted FreighAi invoice {} to DRAFT for edit by {}", freighaiInvoiceId, userEmail
                )
            }
        }

        val genericUpdateBase = if (
            invoice.generationContractVersion == WarehouseJobGenerationContracts.V1 && needsRevert
        ) {
            freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken)
                ?: throw InvoiceEditConflictException(
                    "The invoice was reverted to draft, but its new revision could not be read. " +
                        "Open it in FreighAI before retrying."
                )
        } else before

        // ── Push the new line list ──────────────────────────────────────
        val updated = when (
            val result = freighAiInvoiceClient.updateInvoice(
                freighaiInvoiceId,
                UpdateFreighAiInvoiceRequest(
                    lineItems = payloadLines.map { it.toFreighAiLine() },
                    expectedDocumentRevision = if (
                        invoice.generationContractVersion == WarehouseJobGenerationContracts.V1
                    ) genericUpdateBase.documentRevision else null
                ),
                authToken
            )
        ) {
            is InvoiceUpdateResult.Success -> result.invoice
            is InvoiceUpdateResult.Failure -> {
                // A definite rejection means nothing changed, so an invoice we
                // pulled back can safely be put straight back where it was.
                if (needsRevert) restoreSentState(freighaiInvoiceId, authToken)
                throw InvoiceEditConflictException("FreighAi rejected the edit: ${result.errorMessage}")
            }
            is InvoiceUpdateResult.Indeterminate -> {
                // The update may have landed. Re-sending here could re-issue an
                // invoice carrying half-applied changes, so stop and hand it to
                // a human instead of guessing.
                logger.error(
                    "Invoice {} edit outcome unknown: {}. Left as-is for manual review.",
                    freighaiInvoiceId, result.errorMessage
                )
                throw InvoiceEditConflictException(
                    "FreighAi did not respond, so it is unclear whether your changes were applied " +
                        "(${result.errorMessage}). Nothing further was attempted. Open the invoice in " +
                        "FreighAi to check its current state before retrying" +
                        if (needsRevert) " — it may be sitting in draft." else "."
                )
            }
        }

        if (invoice.generationContractVersion == WarehouseJobGenerationContracts.V1) {
            rebuildWarehouseAllocations(invoice, updated, authToken)
        }

        // ── Put it back in front of the customer ────────────────────────
        var revertCycleCompleted = false
        var warning: String? = null
        if (needsRevert) {
            when (val send = freighAiInvoiceClient.sendInvoice(
                freighaiInvoiceId, "Re-issued after WMS edit: $reason", authToken
            )) {
                is SendResult.Success -> revertCycleCompleted = true
                is SendResult.Failure -> {
                    // The edit applied, but the invoice is no longer in front of
                    // the customer. Reporting a plain success here would leave an
                    // admin believing a sent invoice is still sent when it is not.
                    logger.error(
                        "Invoice {} was edited but could not be re-sent: {}. It is sitting in DRAFT.",
                        freighaiInvoiceId, send.errorMessage
                    )
                    warning = "Your changes were saved, but the invoice could not be re-issued " +
                        "(${send.errorMessage}). It is currently a draft in FreighAi and the customer " +
                        "cannot see it — use Send to re-issue it."
                }
            }
        }

        // Always re-read after a revert cycle: `updated` is the PUT response,
        // captured while the invoice was necessarily still DRAFT, so persisting
        // it would record a sent invoice as a draft. On the plain-DRAFT path the
        // PUT response is already current; re-fetch only if it arrived without
        // lines (older builds return a lighter projection on PUT).
        val authoritative = if (needsRevert || updated.lineItems.isEmpty()) {
            freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken) ?: updated
        } else {
            updated
        }

        val zeroedLines = submittedLines.filter { amountOf(it).signum() == 0 }
        val persistedLines = buildPersistedLines(authoritative, zeroedLines, submittedLines, invoice)

        // Re-read before writing. `invoice` was loaded before three external
        // calls, and the status sync (or a concurrent edit) may have written to
        // this document meanwhile. Copying onto the stale snapshot would drop
        // whatever landed in between — including a prior edit's audit entry.
        val current = invoiceRepository.findById(billingInvoiceId).orElse(invoice)

        val saved = invoiceRepository.save(
            current.copy(
                manuallyEdited = true,
                editedLineItems = persistedLines,
                // Fall back to `current`, not `invoice` — falling back to the
                // pre-read snapshot would undo a concurrent write on exactly the
                // fields this re-read exists to protect.
                subtotal = authoritative.subtotal ?: current.subtotal,
                totalVat = authoritative.totalVatAmount ?: current.totalVat,
                grandTotal = authoritative.grandTotal ?: current.grandTotal,
                freighaiStatus = authoritative.currentStatus,
                freighaiInvoiceDate = authoritative.invoiceDate ?: current.freighaiInvoiceDate,
                freighaiDueDate = authoritative.dueDate ?: current.freighaiDueDate,
                freighaiOutstandingAmount = authoritative.outstandingAmount,
                warehouseJobStatus = deriveWarehouseJobLifecycle(
                    current.generationContractVersion, current.warehouseJobStatus, authoritative.currentStatus
                ),
                lastSyncedAt = Instant.now(),
                editHistory = current.editHistory + InvoiceEditEntry(
                    editedAt = Instant.now(),
                    editedBy = userEmail,
                    reason = reason,
                    changes = diff(currentLines, submittedLines),
                    freighaiStatusBefore = before.currentStatus,
                    subtotalBefore = before.subtotal,
                    subtotalAfter = authoritative.subtotal,
                    grandTotalBefore = before.grandTotal,
                    grandTotalAfter = authoritative.grandTotal
                )
            )
        )

        logger.info(
            "Invoice {} edited by {} — {} lines ({} zeroed), grandTotal {} → {}, revertCycle={}",
            billingInvoiceId, userEmail, request.lines.size, zeroedLines.size,
            before.grandTotal, authoritative.grandTotal, needsRevert
        )

        return InvoiceEditResult(
            billingInvoiceId = saved.billingInvoiceId,
            freighaiInvoiceId = saved.freighaiInvoiceId,
            freighaiInvoiceNo = saved.freighaiInvoiceNo,
            freighaiStatus = saved.freighaiStatus,
            subtotal = saved.subtotal,
            totalVat = saved.totalVat,
            grandTotal = saved.grandTotal,
            lines = persistedLines,
            revertCycleRan = revertCycleCompleted,
            zeroedLineCount = zeroedLines.size,
            warning = warning
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun loadInvoice(billingInvoiceId: String): WmsBillingInvoice =
        invoiceRepository.findById(billingInvoiceId).orElseThrow {
            IllegalArgumentException("WmsBillingInvoice '$billingInvoiceId' not found")
        }

    /**
     * Best-effort restore after a failed update on an invoice we had pulled
     * back from SENT. Never throws — the caller is already reporting the real
     * failure and must not have it masked by a secondary one.
     */
    private fun restoreSentState(freighaiInvoiceId: String, authToken: String) {
        try {
            when (val send = freighAiInvoiceClient.sendInvoice(
                freighaiInvoiceId, "Restored after failed WMS edit", authToken
            )) {
                is SendResult.Success -> logger.info(
                    "Restored invoice {} to SENT after a failed edit", freighaiInvoiceId
                )
                is SendResult.Failure -> logger.error(
                    "Invoice {} is stuck in DRAFT after a failed edit — could not re-send: {}",
                    freighaiInvoiceId, send.errorMessage
                )
            }
        } catch (e: Exception) {
            logger.error("Invoice {} is stuck in DRAFT after a failed edit", freighaiInvoiceId, e)
        }
    }

    /**
     * The line list the admin sees and edits: FreighAi's live lines, followed
     * by any lines a previous edit zeroed.
     *
     * Zeroed lines exist only in WMS — FreighAi would reject them — so without
     * this merge a zeroed line would vanish from the screen and could never be
     * restored. Their line numbers continue past FreighAi's so the two sets
     * never collide.
     */
    private fun mergeLines(
        freighai: FreighAiInvoiceResponse,
        invoice: WmsBillingInvoice
    ): List<EditableLine> {
        val storedByKey = (invoice.editedLineItems ?: emptyList())
            .associateBy { manualKey(it.description, it.chargeTypeId) }

        val live = freighai.lineItems.map { line ->
            EditableLine(
                lineNo = line.lineNo,
                lineId = line.lineId,
                description = line.description,
                quantity = line.quantity,
                unit = line.unit,
                unitPrice = line.unitPrice,
                amount = line.amount,
                chargeTypeId = line.chargeTypeId,
                chargeTypeLabel = line.chargeTypeLabel,
                vatPercent = line.vatPercent,
                vatAmount = line.vatAmount,
                ledgerId = line.ledgerId,
                warehouseAccountingCategory = line.warehouseAccountingCategory,
                // Display-only badge; a miss just means no badge.
                isManual = storedByKey[manualKey(line.description, line.chargeTypeId)]?.isManual ?: false,
                isZeroed = false
            )
        }

        val nextLineNo = (live.maxOfOrNull { it.lineNo } ?: 0) + 1
        val zeroed = (invoice.editedLineItems ?: emptyList())
            .filter { it.isZeroed }
            .mapIndexed { idx, it ->
                EditableLine(
                    lineNo = nextLineNo + idx,
                    lineId = it.lineId,
                    description = it.description,
                    quantity = it.quantity,
                    unit = it.unit,
                    unitPrice = BigDecimal.ZERO,
                    amount = BigDecimal.ZERO,
                    chargeTypeId = it.chargeTypeId,
                    chargeTypeLabel = it.chargeTypeLabel,
                    vatPercent = it.vatPercent,
                    vatAmount = BigDecimal.ZERO,
                    ledgerId = it.ledgerId,
                    warehouseAccountingCategory = it.warehouseAccountingCategory,
                    isManual = it.isManual,
                    isZeroed = true
                )
            }

        return live + zeroed
    }

    private fun manualKey(description: String, chargeTypeId: String?) =
        "${description.trim().lowercase()}|${chargeTypeId.orEmpty()}"

    /**
     * Re-hydrates server-owned line metadata before an update. Generic
     * Warehouse invoices reject index-based identities, so original lines
     * must echo their stable ID and an ad-hoc line gets a retry-stable ID.
     */
    private fun prepareSubmittedLines(
        invoice: WmsBillingInvoice,
        current: List<EditableLine>,
        request: EditInvoiceLinesRequest
    ): List<EditLineRequest> {
        val warehouseManaged = invoice.generationContractVersion == WarehouseJobGenerationContracts.V1
        val currentByNo = current.associateBy { it.lineNo }
        val prepared = request.lines.mapIndexed { index, line ->
            val existing = line.lineNo?.let(currentByNo::get)
            if (existing?.lineId != null && line.lineId != null && existing.lineId != line.lineId.trim()) {
                throw InvoiceEditConflictException(
                    "This invoice changed since you opened it — line ${line.lineNo} has a different identity."
                )
            }
            if (existing?.warehouseAccountingCategory != null && line.warehouseAccountingCategory != null &&
                existing.warehouseAccountingCategory != line.warehouseAccountingCategory
            ) {
                throw InvoiceEditConflictException(
                    "The accounting category of existing line ${line.lineNo} cannot be changed."
                )
            }

            val stableLineId = when {
                existing != null -> existing.lineId
                warehouseManaged -> manualWarehouseLineId(invoice.billingInvoiceId, request.baseVersion, index, line)
                else -> line.lineId?.trim()?.takeIf(String::isNotEmpty)
            }
            val category = existing?.warehouseAccountingCategory
                ?: line.warehouseAccountingCategory?.trim()?.takeIf(String::isNotEmpty)
            line.copy(lineId = stableLineId, warehouseAccountingCategory = category)
        }

        val duplicateIds = prepared.mapNotNull { it.lineId }.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            throw InvoiceEditConflictException("Stable invoice line identities must be unique.")
        }
        if (warehouseManaged) {
            prepared.firstOrNull {
                amountOf(it).signum() > 0 && it.warehouseAccountingCategory !in WAREHOUSE_ACCOUNTING_CATEGORIES
            }?.let {
                throw InvoiceEditConflictException(
                    "Line \"${it.description}\" needs a warehouse accounting category."
                )
            }
        }
        return prepared
    }

    private fun manualWarehouseLineId(
        billingInvoiceId: String,
        baseVersion: String,
        index: Int,
        line: EditLineRequest
    ): String {
        val material = listOf(
            billingInvoiceId,
            baseVersion,
            index.toString(),
            line.description.trim(),
            line.chargeTypeId.orEmpty(),
            line.quantity.stripTrailingZeros().toPlainString(),
            line.unitPrice.stripTrailingZeros().toPlainString()
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "iline_${digest.take(32)}"
    }

    /**
     * Removal is not supported, so every line currently on the invoice must
     * come back. An unknown line number means the client is working from a
     * stale view.
     */
    private fun validateNoRemovals(current: List<EditableLine>, submitted: List<EditLineRequest>) {
        val currentNos = current.map { it.lineNo }.toSet()
        val submittedNos = submitted.mapNotNull { it.lineNo }

        val duplicates = submittedNos.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw InvoiceEditConflictException(
                "Line ${duplicates.sorted().joinToString(", ")} appears more than once in the request."
            )
        }

        val missing = currentNos - submittedNos.toSet()
        if (missing.isNotEmpty()) {
            val labels = current.filter { it.lineNo in missing }.joinToString(", ") { "\"${it.description}\"" }
            throw InvoiceEditConflictException(
                "Lines cannot be removed from an invoice ($labels). Set the unit price to 0 instead — " +
                    "the line stays on the record and the underlying warehouse activity stays accounted for."
            )
        }

        val unknown = submittedNos.toSet() - currentNos
        if (unknown.isNotEmpty()) {
            throw InvoiceEditConflictException(
                "This invoice changed since you opened it — reload and reapply your changes."
            )
        }
    }

    /** Mirrors FreighAi's own `resolveLineItems` rounding so previews agree. */
    private fun amountOf(line: EditLineRequest): BigDecimal =
        line.quantity.multiply(line.unitPrice).setScale(2, RoundingMode.HALF_UP)

    private fun EditLineRequest.toFreighAiLine() = FreighAiInvoiceLineItem(
        lineId = lineId,
        description = description,
        quantity = quantity,
        unit = unit,
        // Non-null here: callers are screened by the charge-type check above.
        chargeTypeId = chargeTypeId!!,
        unitPrice = unitPrice,
        vatPercent = vatPercent,
        vatAmount = vatPercent?.takeIf { it.signum() > 0 }?.let {
            amountOf(this).multiply(it).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
        },
        chargeTypeLabel = chargeTypeLabel,
        ledgerId = ledgerId,
        warehouseAccountingCategory = warehouseAccountingCategory
    )

    /**
     * What gets stored: FreighAi's post-update lines (authoritative for
     * everything it accepted), then the zeroed lines it never saw.
     */
    private fun buildPersistedLines(
        freighai: FreighAiInvoiceResponse,
        zeroed: List<EditLineRequest>,
        allSubmitted: List<EditLineRequest>,
        invoice: WmsBillingInvoice
    ): List<EditedLineItem> {
        val previouslyManual = (invoice.editedLineItems ?: emptyList())
            .filter { it.isManual }
            .map { manualKey(it.description, it.chargeTypeId) }
            .toSet()
        // A line that arrived without a FreighAi line number on this request is
        // new, and stays flagged as manual on every later edit. Derived from the
        // whole submitted list, not just the zeroed subset — the common case is
        // a hand-added line that is billable, and keying off `zeroed` alone
        // meant those were never marked at all.
        val newlyManual = allSubmitted.filter { it.lineNo == null }
            .map { manualKey(it.description, it.chargeTypeId) }
            .toSet()

        val accepted = freighai.lineItems.map { it.toEdited(previouslyManual + newlyManual) }
        val nextLineNo = (accepted.maxOfOrNull { it.lineNo } ?: 0) + 1

        val withheld = zeroed.mapIndexed { idx, line ->
            EditedLineItem(
                lineNo = nextLineNo + idx,
                lineId = line.lineId,
                description = line.description,
                quantity = line.quantity,
                unit = line.unit,
                unitPrice = BigDecimal.ZERO,
                amount = BigDecimal.ZERO,
                chargeTypeId = line.chargeTypeId,
                chargeTypeLabel = line.chargeTypeLabel,
                vatPercent = line.vatPercent,
                vatAmount = BigDecimal.ZERO,
                ledgerId = line.ledgerId,
                warehouseAccountingCategory = line.warehouseAccountingCategory,
                isManual = line.lineNo == null
                    || manualKey(line.description, line.chargeTypeId) in previouslyManual,
                isZeroed = true
            )
        }
        return accepted + withheld
    }

    private fun FreighAiInvoiceLineItemResponse.toEdited(manualKeys: Set<String>) = EditedLineItem(
        lineNo = lineNo,
        lineId = lineId,
        description = description,
        quantity = quantity,
        unit = unit,
        unitPrice = unitPrice,
        amount = amount,
        chargeTypeId = chargeTypeId,
        chargeTypeLabel = chargeTypeLabel,
        vatPercent = vatPercent,
        vatAmount = vatAmount,
        ledgerId = ledgerId,
        warehouseAccountingCategory = warehouseAccountingCategory,
        isManual = manualKey(description, chargeTypeId) in manualKeys,
        isZeroed = false
    )

    /**
     * Cheap optimistic-concurrency token. Two admins editing the same invoice,
     * or a change made in FreighAi while the WMS screen was open, must not
     * result in one silently overwriting the other on a financial document.
     */
    private fun versionOf(freighai: FreighAiInvoiceResponse): String =
        listOf(
            freighai.currentStatus.orEmpty(),
            freighai.documentRevision.toString(),
            freighai.lineItems.size.toString(),
            freighai.grandTotal?.stripTrailingZeros()?.toPlainString().orEmpty(),
            // Every field the edit screen can change has to be in the token.
            // Hashing only amounts would let two admins who each changed just a
            // description (or unit, or charge type) silently overwrite one
            // another — the totals would be identical, so nothing would flag it.
            freighai.lineItems.joinToString(",") {
                listOf(
                    it.lineNo.toString(),
                    it.lineId.orEmpty(),
                    it.amount.stripTrailingZeros().toPlainString(),
                    it.quantity.stripTrailingZeros().toPlainString(),
                    it.unitPrice.stripTrailingZeros().toPlainString(),
                    it.description,
                    it.unit,
                    it.chargeTypeId.orEmpty(),
                    it.vatPercent?.stripTrailingZeros()?.toPlainString().orEmpty(),
                    it.ledgerId.orEmpty(),
                    it.warehouseAccountingCategory.orEmpty()
                ).joinToString(":")
            }
        ).joinToString("|")

    private fun rebuildWarehouseAllocations(
        local: WmsBillingInvoice,
        remote: FreighAiInvoiceResponse,
        authToken: String
    ) {
        val jobId = local.warehouseJobId
            ?: throw InvoiceEditConflictException("Warehouse Job binding is missing; allocations cannot be rebuilt.")
        val currency = remote.currency?.code ?: local.warehouseJobCurrencyCode
            ?: throw InvoiceEditConflictException("Invoice currency is unavailable; allocations cannot be rebuilt.")
        val expected = remote.lineItems.map { line ->
            val lineId = line.lineId
                ?: throw InvoiceEditConflictException("FreighAI returned a generic invoice line without a stable line ID.")
            val vat = line.vatAmount ?: BigDecimal.ZERO
            FreighAiJobAllocationItem(
                allocationKey = "sales:${remote.invoiceId}:$lineId:$jobId",
                invoiceLineId = lineId,
                target = "JOB",
                jobId = jobId,
                // Original WMS selling lines use the 32-hex payload identity.
                // Manual invoice-only additions use `iline_...` and therefore
                // link to the Job without pretending to be an operational line.
                jobLineId = lineId.takeIf { WAREHOUSE_SELLING_LINE_ID.matches(it) },
                netAmount = line.amount,
                vatAmount = vat,
                grossAmount = line.amount.add(vat),
                documentCurrency = currency,
                baseCurrency = currency,
                baseNetAmount = line.amount,
                baseVatAmount = vat,
                baseGrossAmount = line.amount.add(vat)
            )
        }
        when (val result = freighAiInvoiceClient.replaceJobAllocationsV1(
            remote.invoiceId,
            ReplaceFreighAiJobAllocationsRequest(remote.allocationRevision, expected),
            "wms-edit:${remote.invoiceId}:${remote.documentRevision}",
            authToken,
            "wms-invoice-edit"
        )) {
            is InvoiceAllocationMutationResult.Success -> return
            is InvoiceAllocationMutationResult.Rejected -> {
                val actual = freighAiInvoiceClient.getJobAllocationsV1(remote.invoiceId, authToken)
                if (actual is InvoiceAllocationLookupResult.Found && allocationsMatch(actual.allocations, expected)) return
                throw InvoiceEditConflictException(
                    "The invoice lines were updated, but Warehouse Job allocations could not be rebuilt: ${result.errorMessage}. " +
                        "The invoice remains in draft for recovery."
                )
            }
            is InvoiceAllocationMutationResult.Indeterminate -> {
                val actual = freighAiInvoiceClient.getJobAllocationsV1(remote.invoiceId, authToken)
                if (actual is InvoiceAllocationLookupResult.Found && allocationsMatch(actual.allocations, expected)) return
                throw InvoiceEditConflictException(
                    "The invoice lines were updated, but the allocation result is uncertain: ${result.errorMessage}. " +
                        "The invoice remains in draft for recovery."
                )
            }
        }
    }

    private fun allocationsMatch(
        actual: List<FreighAiJobAllocationResponse>,
        expected: List<FreighAiJobAllocationItem>
    ): Boolean {
        if (actual.size != expected.size) return false
        val byKey = actual.associateBy { it.allocationKey }
        return expected.all { wanted ->
            byKey[wanted.allocationKey]?.let { found ->
                found.invoiceLineId == wanted.invoiceLineId &&
                    found.jobId == wanted.jobId &&
                    found.jobLineId == wanted.jobLineId &&
                    found.netAmount.compareTo(wanted.netAmount) == 0 &&
                    found.vatAmount.compareTo(wanted.vatAmount) == 0 &&
                    found.grossAmount.compareTo(wanted.grossAmount) == 0
            } == true
        }
    }

    /** Field-level diff for the audit trail. */
    private fun diff(before: List<EditableLine>, after: List<EditLineRequest>): List<EditedFieldChange> {
        val beforeByNo = before.associateBy { it.lineNo }
        val changes = mutableListOf<EditedFieldChange>()

        for (line in after) {
            val ref = "Line ${line.lineNo ?: "new"} (${line.description})"
            val prior = line.lineNo?.let { beforeByNo[it] }
            if (prior == null) {
                changes += EditedFieldChange(
                    lineRef = ref,
                    field = "added",
                    oldValue = null,
                    newValue = "${line.quantity.toPlainString()} ${line.unit} " +
                        "× ${line.unitPrice.toPlainString()} = ${amountOf(line).toPlainString()}"
                )
                continue
            }
            if (prior.description != line.description) {
                changes += EditedFieldChange(ref, "description", prior.description, line.description)
            }
            if (prior.quantity.compareTo(line.quantity) != 0) {
                changes += EditedFieldChange(
                    ref, "quantity", prior.quantity.toPlainString(), line.quantity.toPlainString()
                )
            }
            if (prior.unitPrice.compareTo(line.unitPrice) != 0) {
                changes += EditedFieldChange(
                    ref, "unitPrice", prior.unitPrice.toPlainString(), line.unitPrice.toPlainString()
                )
            }
            if (prior.unit != line.unit) {
                changes += EditedFieldChange(ref, "unit", prior.unit, line.unit)
            }
            if (prior.chargeTypeId != line.chargeTypeId) {
                changes += EditedFieldChange(
                    ref, "chargeTypeId", prior.chargeTypeId, line.chargeTypeId
                )
            }
            if (prior.ledgerId != line.ledgerId) {
                changes += EditedFieldChange(ref, "ledgerId", prior.ledgerId, line.ledgerId)
            }
            if ((prior.vatPercent ?: BigDecimal.ZERO).compareTo(line.vatPercent ?: BigDecimal.ZERO) != 0) {
                changes += EditedFieldChange(
                    ref, "vatPercent",
                    prior.vatPercent?.toPlainString(), line.vatPercent?.toPlainString()
                )
            }
            // Emitted from the withhold decision itself, not from a unitPrice
            // change: a line can also be withheld by zeroing its quantity, and
            // that transition must show up in the audit trail either way.
            val nowZero = amountOf(line).signum() == 0
            if (nowZero != prior.isZeroed) {
                changes += EditedFieldChange(
                    ref, if (nowZero) "zeroed" else "restored",
                    if (prior.isZeroed) "withheld from invoice" else "billable",
                    if (nowZero) "withheld from invoice" else "billable"
                )
            }
        }
        return changes
    }

    private fun notEditable(
        invoice: WmsBillingInvoice,
        freighai: FreighAiInvoiceResponse?,
        reason: String
    ) = InvoiceEditableView(
        billingInvoiceId = invoice.billingInvoiceId,
        freighaiInvoiceId = invoice.freighaiInvoiceId,
        freighaiInvoiceNo = invoice.freighaiInvoiceNo,
        freighaiStatus = freighai?.currentStatus ?: invoice.freighaiStatus,
        editable = false,
        blockedReason = reason,
        requiresRevert = false,
        lines = emptyList(),
        subtotal = invoice.subtotal,
        totalVat = invoice.totalVat,
        grandTotal = invoice.grandTotal,
        manuallyEdited = invoice.manuallyEdited,
        editHistory = invoice.editHistory,
        warehouseJobManaged = invoice.generationContractVersion == WarehouseJobGenerationContracts.V1,
        baseVersion = ""
    )
}
