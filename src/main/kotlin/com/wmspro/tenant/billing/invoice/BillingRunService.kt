package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.common.external.freighai.client.InvoiceCreationResult
import com.wmspro.common.external.freighai.dto.CreateFreighAiInvoiceRequest
import com.wmspro.common.external.freighai.dto.FreighAiChargeType
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItem
import com.wmspro.tenant.billing.catalog.ServiceCatalogRepository
import com.wmspro.tenant.billing.invoice.aggregator.MovementAggregator
import com.wmspro.tenant.billing.invoice.aggregator.OccupancyAggregator
import com.wmspro.tenant.billing.invoice.aggregator.ServiceLogAggregator
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.profile.CustomerBillingProfile
import com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository
import com.wmspro.tenant.billing.profile.ProjectRate
import com.wmspro.tenant.dto.GetOrAssignRequestItem
import com.wmspro.tenant.repository.AccountIdMappingRepository
import com.wmspro.tenant.service.AccountIdMappingService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * BillingRunService — orchestrates the per-(customer, month) billing run.
 *
 * Lifecycle:
 *  1. **preview** — runs the three aggregators, returns a [BillingPreviewResponse]
 *     with line projections + data-quality warnings. No persistence; caller
 *     uses this to populate Phase 6's pre-billing modal.
 *  2. **generate** — full submission flow:
 *      a. Idempotency: short-circuit if WmsBillingInvoice exists in
 *         {DRAFT, SUBMITTED, SUBMISSION_FAILED-with-FreighAi-binding}.
 *      b. Run aggregators, resolve rates from BillingProfile, compute totals.
 *      c. Pre-create WmsBillingInvoice in DRAFT.
 *      d. Lock-cascade (ServiceLog → GRN → GIN). On cascade fail, abort
 *         and clean up.
 *      e. Query FreighAi by referenceNo (idempotency layer 2).
 *      f. POST CreateInvoiceRequest to FreighAi.
 *      g. On success → save SUBMITTED with FreighAi binding.
 *         On failure → rollback locks, save SUBMISSION_FAILED.
 *  3. **cancel** — cancels FreighAi invoice + clears lock cascade. Status
 *     goes to CANCELLED. Source records become eligible for re-billing.
 *
 * Auth: caller passes the FreighAi JWT (`authToken`). Cron will obtain a
 * service-account JWT in Phase 10; admin-triggered runs forward the
 * caller's JWT. Same pattern as CustomerMasterProxyService.
 */
@Service
class BillingRunService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val billingProfileRepository: CustomerBillingProfileRepository,
    private val catalogRepository: ServiceCatalogRepository,
    private val accountIdMappingService: AccountIdMappingService,
    private val accountIdMappingRepository: AccountIdMappingRepository,
    private val occupancyAggregator: OccupancyAggregator,
    private val movementAggregator: MovementAggregator,
    private val serviceLogAggregator: ServiceLogAggregator,
    private val freighAiInvoiceClient: FreighAiInvoiceClient,
    private val freighAiChargeTypeClient: FreighAiChargeTypeClient,
    private val cascadeClient: WmsInternalCascadeClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.aed-currency-id:CUR-AED}")
    private lateinit var aedCurrencyId: String

    // ──────────────────────────────────────────────────────────────────
    // Preview
    // ──────────────────────────────────────────────────────────────────

    fun preview(customerId: Long, billingMonth: String, authToken: String): BillingPreviewResponse {
        val ym = YearMonth.parse(billingMonth)
        val existing = invoiceRepository.findByCustomerIdAndBillingMonth(customerId, billingMonth)
        val alreadyGenerated = existing != null && existing.status in setOf(
            BillingInvoiceStatus.SUBMITTED, BillingInvoiceStatus.DRAFT
        )

        val context = buildContext(customerId, ym, authToken, dryRun = true)
            ?: return emptyPreview(customerId, billingMonth, alreadyGenerated, existing?.billingInvoiceId,
                blocker = "No active billing profile for customer $customerId")

        return BillingPreviewResponse(
            customerId = customerId,
            billingMonth = billingMonth,
            storageLines = context.storageLines,
            movementLines = context.movementLines,
            serviceLines = context.serviceLines,
            subtotal = context.subtotal,
            totalVat = context.totalVat,
            grandTotal = context.grandTotal,
            minimumChargeApplied = context.minimumChargeApplied,
            dataQualityWarnings = context.warnings,
            canGenerate = !alreadyGenerated && context.warnings.none { it.severity == WarningSeverity.BLOCKER },
            alreadyGenerated = alreadyGenerated,
            existingInvoiceId = existing?.billingInvoiceId
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Generate
    // ──────────────────────────────────────────────────────────────────

    @Transactional
    fun generate(
        customerId: Long,
        billingMonth: String,
        triggeredBy: String,
        authToken: String
    ): WmsBillingInvoice {
        val ym = YearMonth.parse(billingMonth)
        // Idempotency layer 1: existing WmsBillingInvoice in non-final state.
        invoiceRepository.findByCustomerIdAndBillingMonth(customerId, billingMonth)?.let { existing ->
            if (existing.status == BillingInvoiceStatus.SUBMITTED) {
                logger.info("generate: idempotent return for ({}, {}) → {}", customerId, billingMonth, existing.billingInvoiceId)
                return existing
            }
            if (existing.status == BillingInvoiceStatus.DRAFT) {
                throw IllegalStateException(
                    "WmsBillingInvoice ${existing.billingInvoiceId} is mid-flight (DRAFT). " +
                    "Wait for the in-flight run or cancel it before retrying."
                )
            }
            // SUBMISSION_FAILED with FreighAi IDs → ambiguous, surface for admin
            if (existing.status == BillingInvoiceStatus.SUBMISSION_FAILED && existing.freighaiInvoiceId != null) {
                throw IllegalStateException(
                    "Previous attempt for ($customerId, $billingMonth) created FreighAi invoice " +
                    "${existing.freighaiInvoiceId} but cascade failed. Review and cancel via " +
                    "billing-runs/cancel/${existing.billingInvoiceId} before regenerating."
                )
            }
            // SUBMISSION_FAILED without FreighAi binding, or CANCELLED → safe to overwrite
            invoiceRepository.deleteById(existing.billingInvoiceId)
        }

        val context = buildContext(customerId, ym, authToken, dryRun = false)
            ?: throw IllegalStateException("No active billing profile for customer $customerId")

        if (context.warnings.any { it.severity == WarningSeverity.BLOCKER }) {
            val msgs = context.warnings.filter { it.severity == WarningSeverity.BLOCKER }
                .joinToString("; ") { it.message }
            throw IllegalStateException("Billing run blocked by data quality issues: $msgs")
        }

        if (context.storageLines.isEmpty() && context.movementLines.isEmpty() && context.serviceLines.isEmpty()) {
            throw IllegalStateException(
                "No billable activity for customer $customerId in $billingMonth — nothing to invoice."
            )
        }

        val billingInvoiceId = "wmsinv_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val referenceNo = "WMS-$customerId-$billingMonth"

        // Pre-create DRAFT row.
        val now = Instant.now()
        var draft = WmsBillingInvoice(
            billingInvoiceId = billingInvoiceId,
            customerId = customerId,
            billingMonth = billingMonth,
            status = BillingInvoiceStatus.DRAFT,
            storageLines = context.storageLines,
            movementLines = context.movementLines,
            serviceLines = context.serviceLines,
            subtotal = context.subtotal,
            totalVat = context.totalVat,
            grandTotal = context.grandTotal,
            minimumChargeApplied = context.minimumChargeApplied,
            freighaiReferenceNo = referenceNo,
            generatedAt = now,
            generatedBy = triggeredBy,
            submissionAttempts = listOf(SubmissionAttempt(attemptedAt = now, success = false, errorMessage = "in flight"))
        )
        draft = invoiceRepository.save(draft)

        // Lock cascade.
        val grnIds = context.movementLines.filter { it.direction == MovementDirection.INBOUND }
            .flatMap { it.sourceRecordIds }.distinct()
        val ginIds = context.movementLines.filter { it.direction == MovementDirection.OUTBOUND }
            .flatMap { it.sourceRecordIds }.distinct()
        val serviceLogIds = context.serviceLines.flatMap { it.serviceLogIds }.distinct()

        val cascadeOutcome = cascadeClient.setLocks(
            billingInvoiceId = billingInvoiceId,
            billingMonth = billingMonth,
            receivingRecordIds = grnIds,
            fulfillmentIds = ginIds,
            serviceLogIds = serviceLogIds
        )
        if (!cascadeOutcome.isAllSuccess()) {
            // Roll back any locks that did succeed.
            cascadeClient.clearLocks(grnIds, ginIds, serviceLogIds)
            val failed = invoiceRepository.save(
                draft.copy(
                    status = BillingInvoiceStatus.SUBMISSION_FAILED,
                    submissionAttempts = draft.submissionAttempts + SubmissionAttempt(
                        attemptedAt = Instant.now(),
                        success = false,
                        errorMessage = "Lock cascade failed: ${cascadeOutcome.summary()}"
                    )
                )
            )
            throw IllegalStateException("Lock cascade failed (${cascadeOutcome.summary()}); marked as SUBMISSION_FAILED.")
        }

        // FreighAi idempotency layer 2.
        val existingFreighai = freighAiInvoiceClient.findInvoiceByReferenceNo(referenceNo, authToken)

        val freighaiInvoiceId: String
        val freighaiInvoiceNo: String
        val freighaiVoucherId: String?

        if (existingFreighai != null) {
            // Audit fix (Finding 1): refuse to adopt a CANCELLED FreighAi invoice.
            // Adopting a cancelled invoice would lock GRN/GIN/ServiceLog rows to a
            // dead FreighAi record that can never accept payments or further state
            // transitions. Admin must explicitly resolve before regenerating —
            // typically by cancelling our (still non-existent) WMS run, then
            // re-running which will pass the idempotency check and create a fresh
            // FreighAi invoice (because there's no live invoice with this
            // referenceNo any more).
            if (existingFreighai.currentStatus?.equals("CANCELLED", ignoreCase = true) == true) {
                cascadeClient.clearLocks(grnIds, ginIds, serviceLogIds)
                invoiceRepository.save(
                    draft.copy(
                        status = BillingInvoiceStatus.SUBMISSION_FAILED,
                        submissionAttempts = draft.submissionAttempts + SubmissionAttempt(
                            attemptedAt = Instant.now(),
                            success = false,
                            errorMessage = "FreighAi invoice ${existingFreighai.invoiceId} for referenceNo=$referenceNo is CANCELLED; cannot adopt"
                        )
                    )
                )
                throw IllegalStateException(
                    "FreighAi invoice ${existingFreighai.invoiceId} (${existingFreighai.invoiceNo}) " +
                    "for referenceNo=$referenceNo is in CANCELLED state. Cannot adopt a cancelled invoice. " +
                    "If the customer intentionally cancelled this billing month, leave it as-is. If you want " +
                    "to regenerate, manually delete the FreighAi invoice's referenceNo or use a different one."
                )
            }
            logger.warn(
                "FreighAi already has invoice {} ({}) for referenceNo={}; adopting instead of duplicating.",
                existingFreighai.invoiceId, existingFreighai.invoiceNo, referenceNo
            )
            freighaiInvoiceId = existingFreighai.invoiceId
            freighaiInvoiceNo = existingFreighai.invoiceNo
            freighaiVoucherId = existingFreighai.voucherId
        } else {
            val freighaiCustomerId = accountIdMappingRepository.findById(customerId).orElse(null)?.freighaiCustomerId
                ?: run {
                    cascadeClient.clearLocks(grnIds, ginIds, serviceLogIds)
                    invoiceRepository.save(draft.copy(
                        status = BillingInvoiceStatus.SUBMISSION_FAILED,
                        submissionAttempts = draft.submissionAttempts + SubmissionAttempt(
                            attemptedAt = Instant.now(),
                            success = false,
                            errorMessage = "No FreighAi customerId mapping for customerId=$customerId"
                        )
                    ))
                    throw IllegalStateException("No FreighAi customerId mapping for customerId=$customerId")
                }

            val request = CreateFreighAiInvoiceRequest(
                invoiceDate = LocalDate.now(),
                partyId = freighaiCustomerId,
                currencyId = aedCurrencyId,
                referenceNo = referenceNo,
                narration = "WMS storage / movement / service charges for $billingMonth",
                lineItems = context.toFreighAiLineItems()
            )
            when (val result = freighAiInvoiceClient.createInvoice(request, authToken)) {
                is InvoiceCreationResult.Success -> {
                    freighaiInvoiceId = result.invoice.invoiceId
                    freighaiInvoiceNo = result.invoice.invoiceNo
                    freighaiVoucherId = result.invoice.voucherId
                }
                is InvoiceCreationResult.Failure -> {
                    cascadeClient.clearLocks(grnIds, ginIds, serviceLogIds)
                    val failed = invoiceRepository.save(draft.copy(
                        status = BillingInvoiceStatus.SUBMISSION_FAILED,
                        submissionAttempts = draft.submissionAttempts + SubmissionAttempt(
                            attemptedAt = Instant.now(),
                            success = false,
                            errorMessage = "FreighAi create rejected: ${result.errorMessage}"
                        )
                    ))
                    throw IllegalStateException("FreighAi create rejected: ${result.errorMessage}")
                }
            }
        }

        val saved = invoiceRepository.save(
            draft.copy(
                status = BillingInvoiceStatus.SUBMITTED,
                freighaiInvoiceId = freighaiInvoiceId,
                freighaiInvoiceNo = freighaiInvoiceNo,
                freighaiVoucherId = freighaiVoucherId,
                lastSyncedAt = Instant.now(),
                submissionAttempts = draft.submissionAttempts + SubmissionAttempt(
                    attemptedAt = Instant.now(),
                    success = true,
                    errorMessage = null
                )
            )
        )
        logger.info(
            "BillingRun SUBMITTED: customerId={} month={} → freighaiInvoiceNo={} grandTotal={}",
            customerId, billingMonth, saved.freighaiInvoiceNo, saved.grandTotal
        )
        return saved
    }

    // ──────────────────────────────────────────────────────────────────
    // Cancel
    // ──────────────────────────────────────────────────────────────────

    @Transactional
    fun cancel(
        billingInvoiceId: String,
        reason: String,
        userEmail: String,
        authToken: String
    ): WmsBillingInvoice {
        val invoice = invoiceRepository.findById(billingInvoiceId).orElseThrow {
            IllegalArgumentException("WmsBillingInvoice '$billingInvoiceId' not found")
        }
        if (invoice.status == BillingInvoiceStatus.CANCELLED) return invoice

        // Cancel in FreighAi first if a binding exists.
        if (invoice.freighaiInvoiceId != null) {
            val freighaiOk = freighAiInvoiceClient.cancelInvoice(invoice.freighaiInvoiceId, reason, authToken)
            if (!freighaiOk) {
                logger.warn(
                    "FreighAi cancel returned non-2xx for invoice {} — proceeding with local lock-clear; admin may need to retry FreighAi cancel manually.",
                    invoice.freighaiInvoiceId
                )
            }
        }

        // Clear locks across all source records.
        val grnIds = invoice.movementLines.filter { it.direction == MovementDirection.INBOUND }
            .flatMap { it.sourceRecordIds }.distinct()
        val ginIds = invoice.movementLines.filter { it.direction == MovementDirection.OUTBOUND }
            .flatMap { it.sourceRecordIds }.distinct()
        val serviceLogIds = invoice.serviceLines.flatMap { it.serviceLogIds }.distinct()
        val outcome = cascadeClient.clearLocks(grnIds, ginIds, serviceLogIds)
        if (!outcome.isAllSuccess()) {
            logger.warn(
                "Cascade clear-lock for cancel of {} had failures: {} — manual cleanup may be needed.",
                billingInvoiceId, outcome.summary()
            )
        }

        return invoiceRepository.save(
            invoice.copy(
                status = BillingInvoiceStatus.CANCELLED,
                cancelledAt = Instant.now(),
                cancelledBy = userEmail,
                cancelReason = reason
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Build the lines + warnings for a (customer, month). Shared between
     * preview and generate so the math is identical. `dryRun` doesn't gate
     * any DB writes today — kept as a marker in case future logic needs to.
     */
    private fun buildContext(
        customerId: Long,
        billingMonth: YearMonth,
        authToken: String,
        @Suppress("UNUSED_PARAMETER") dryRun: Boolean
    ): BillingContext? {
        val profile = billingProfileRepository.findById(customerId).orElse(null) ?: return null
        if (!profile.billingEnabled) {
            return BillingContext(
                profile = profile,
                storageLines = emptyList(),
                movementLines = emptyList(),
                serviceLines = emptyList(),
                subtotal = BigDecimal.ZERO,
                totalVat = BigDecimal.ZERO,
                grandTotal = BigDecimal.ZERO,
                minimumChargeApplied = null,
                warnings = listOf(DataQualityWarning(
                    severity = WarningSeverity.BLOCKER,
                    code = "BILLING_DISABLED",
                    message = "Billing is disabled for customer $customerId. Enable it on the Billing tab first."
                ))
            )
        }

        val warnings = mutableListOf<DataQualityWarning>()

        // Audit fix (Finding 12): batch-fetch all FreighAi ChargeTypes once,
        // index by id, then look up locally. Replaces the previous per-call
        // pattern (3 fixed + 1 per service-subscription = N+3 calls) with a
        // single round-trip.
        val chargeTypeIndex = freighAiChargeTypeClient.listChargeTypes(authToken, activeOnly = false)
            .associateBy { it.chargeTypeId }
        val storageCt = chargeTypeIndex[profile.freighaiStorageChargeTypeId]
        val inboundCt = chargeTypeIndex[profile.freighaiInboundMovementChargeTypeId]
        val outboundCt = chargeTypeIndex[profile.freighaiOutboundMovementChargeTypeId]
        if (storageCt == null) warnings += DataQualityWarning(
            severity = WarningSeverity.BLOCKER,
            code = "STORAGE_CHARGE_TYPE_MISSING",
            message = "Storage ChargeType '${profile.freighaiStorageChargeTypeId}' not found in FreighAi"
        )
        if (inboundCt == null) warnings += DataQualityWarning(
            severity = WarningSeverity.BLOCKER,
            code = "INBOUND_CHARGE_TYPE_MISSING",
            message = "Inbound ChargeType '${profile.freighaiInboundMovementChargeTypeId}' not found in FreighAi"
        )
        if (outboundCt == null) warnings += DataQualityWarning(
            severity = WarningSeverity.BLOCKER,
            code = "OUTBOUND_CHARGE_TYPE_MISSING",
            message = "Outbound ChargeType '${profile.freighaiOutboundMovementChargeTypeId}' not found in FreighAi"
        )

        // ── Storage lines ────────────────────────────────────────────
        val occupancy = occupancyAggregator.aggregate(customerId, billingMonth)
        val storageLines = mutableListOf<StorageLine>()
        var storageSubtotal = BigDecimal.ZERO
        if (storageCt != null) {
            for ((projectCode, cbmDays) in occupancy.cbmDaysByProject) {
                if (cbmDays.signum() == 0) continue
                val (rate, projectLabel) = resolveStorageRate(profile, projectCode)
                if (rate.signum() == 0) {
                    warnings += DataQualityWarning(
                        severity = WarningSeverity.WARNING,
                        code = "STORAGE_RATE_ZERO",
                        message = "Storage rate for project '${projectCode ?: "Unassigned"}' is 0 — line skipped",
                        affectedIds = listOf(projectCode ?: "_default_")
                    )
                    continue
                }
                val amount = cbmDays.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                val vatAmt = amount.multiply(storageCt.vatPercent).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                storageLines += StorageLine(
                    projectCode = projectCode,
                    projectLabel = projectLabel,
                    cbmDays = cbmDays,
                    ratePerDay = rate,
                    amount = amount,
                    vatPercent = storageCt.vatPercent,
                    vatAmount = vatAmt,
                    description = "Storage – ${projectLabel ?: "Unassigned"} – ${formatMonth(billingMonth)}",
                    freighaiChargeTypeId = profile.freighaiStorageChargeTypeId
                )
                storageSubtotal = storageSubtotal.add(amount)
            }
        }
        // Apply minimum.
        var minimumApplied: BigDecimal? = null
        if (profile.defaultMonthlyMinimum != null
            && storageSubtotal < profile.defaultMonthlyMinimum
            && storageCt != null) {
            val gap = profile.defaultMonthlyMinimum.subtract(storageSubtotal).setScale(2, RoundingMode.HALF_UP)
            if (gap.signum() > 0) {
                val vatAmt = gap.multiply(storageCt.vatPercent).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                storageLines += StorageLine(
                    projectCode = null,
                    projectLabel = "Monthly minimum top-up",
                    cbmDays = BigDecimal.ZERO,
                    ratePerDay = BigDecimal.ZERO,
                    amount = gap,
                    vatPercent = storageCt.vatPercent,
                    vatAmount = vatAmt,
                    description = "Storage minimum top-up – ${formatMonth(billingMonth)}",
                    freighaiChargeTypeId = profile.freighaiStorageChargeTypeId,
                    isMinimumTopUp = true
                )
                minimumApplied = gap
                storageSubtotal = storageSubtotal.add(gap)
            }
        }
        for (w in occupancy.warnings) {
            warnings += DataQualityWarning(
                severity = WarningSeverity.WARNING,
                code = w.code,
                message = "Item ${w.affectedId} contributed 0 CBM (missing dimensions)",
                affectedIds = listOf(w.affectedId)
            )
        }

        // ── Movement lines ───────────────────────────────────────────
        val movementLines = mutableListOf<MovementLine>()
        if (inboundCt != null) {
            val inbound = movementAggregator.aggregateInbound(customerId, billingMonth)
            for ((projectCode, bucket) in inbound.byProject) {
                val (rate, projectLabel) = resolveInboundRate(profile, projectCode)
                if (rate == null || rate.signum() == 0) continue  // null rate = no inbound charge
                val amount = bucket.totalCbm.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                val vatAmt = amount.multiply(inboundCt.vatPercent).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                movementLines += MovementLine(
                    direction = MovementDirection.INBOUND,
                    projectCode = projectCode,
                    projectLabel = projectLabel,
                    totalCbm = bucket.totalCbm,
                    ratePerCbm = rate,
                    amount = amount,
                    vatPercent = inboundCt.vatPercent,
                    vatAmount = vatAmt,
                    description = "Inbound movement – ${projectLabel ?: "Unassigned"} – ${formatMonth(billingMonth)}",
                    freighaiChargeTypeId = profile.freighaiInboundMovementChargeTypeId,
                    sourceRecordIds = bucket.sourceRecordIds.toList()
                )
            }
            for (w in inbound.warnings) warnings += DataQualityWarning(
                severity = WarningSeverity.WARNING,
                code = w.code,
                message = "Inbound record ${w.recordId} had partial dimensions",
                affectedIds = listOf(w.recordId)
            )
        }
        if (outboundCt != null) {
            val outbound = movementAggregator.aggregateOutbound(customerId, billingMonth)
            for ((projectCode, bucket) in outbound.byProject) {
                val (rate, projectLabel) = resolveOutboundRate(profile, projectCode)
                if (rate == null || rate.signum() == 0) continue
                val amount = bucket.totalCbm.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                val vatAmt = amount.multiply(outboundCt.vatPercent).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                movementLines += MovementLine(
                    direction = MovementDirection.OUTBOUND,
                    projectCode = projectCode,
                    projectLabel = projectLabel,
                    totalCbm = bucket.totalCbm,
                    ratePerCbm = rate,
                    amount = amount,
                    vatPercent = outboundCt.vatPercent,
                    vatAmount = vatAmt,
                    description = "Outbound movement – ${projectLabel ?: "Unassigned"} – ${formatMonth(billingMonth)}",
                    freighaiChargeTypeId = profile.freighaiOutboundMovementChargeTypeId,
                    sourceRecordIds = bucket.sourceRecordIds.toList()
                )
            }
            for (w in outbound.warnings) warnings += DataQualityWarning(
                severity = WarningSeverity.WARNING,
                code = w.code,
                message = "Outbound record ${w.recordId} had partial dimensions",
                affectedIds = listOf(w.recordId)
            )
        }

        // ── Service lines ────────────────────────────────────────────
        val serviceLines = mutableListOf<ServiceLine>()
        val aggregated = serviceLogAggregator.aggregate(customerId, billingMonth)
        if (aggregated.isNotEmpty()) {
            val catalogByCode = catalogRepository.findAll().associateBy { it.serviceCode }
            for ((serviceCode, agg) in aggregated) {
                val sub = profile.serviceSubscriptions.firstOrNull { it.serviceCode == serviceCode }
                val cat = catalogByCode[serviceCode]
                if (sub == null || !sub.isActive) {
                    warnings += DataQualityWarning(
                        severity = WarningSeverity.WARNING,
                        code = "SERVICE_NOT_SUBSCRIBED",
                        message = "Service '$serviceCode' has logs but no active subscription on the customer's profile",
                        affectedIds = agg.serviceLogIds
                    )
                    continue
                }
                if (cat == null) {
                    warnings += DataQualityWarning(
                        severity = WarningSeverity.BLOCKER,
                        code = "SERVICE_CATALOG_MISSING",
                        message = "Service '$serviceCode' has logs but no catalog entry exists",
                        affectedIds = agg.serviceLogIds
                    )
                    continue
                }
                val rate = sub.customRatePerUnit ?: cat.standardRatePerUnit
                val vatPct = cat.vatPercent ?: BigDecimal.ZERO
                // Use the prefetched chargeTypeIndex (Finding 12 fix) to avoid an N+1 round-trip per service.
                val ctForVat = chargeTypeIndex[cat.freighaiChargeTypeId]
                val effectiveVat = cat.vatPercent ?: ctForVat?.vatPercent ?: BigDecimal.ZERO
                val amount = agg.quantity.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                val vatAmt = amount.multiply(effectiveVat).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
                serviceLines += ServiceLine(
                    serviceCode = serviceCode,
                    serviceLabel = cat.label,
                    unit = cat.unit,
                    quantity = agg.quantity,
                    ratePerUnit = rate,
                    amount = amount,
                    vatPercent = effectiveVat,
                    vatAmount = vatAmt,
                    description = "${cat.label} – ${formatMonth(billingMonth)}",
                    freighaiChargeTypeId = cat.freighaiChargeTypeId,
                    serviceLogIds = agg.serviceLogIds
                )
            }
        }

        // ── Totals ───────────────────────────────────────────────────
        val subtotalAll = (storageLines.sumOf { it.amount }
            + movementLines.sumOf { it.amount }
            + serviceLines.sumOf { it.amount }).setScale(2, RoundingMode.HALF_UP)
        val totalVat = (storageLines.sumOf { it.vatAmount }
            + movementLines.sumOf { it.vatAmount }
            + serviceLines.sumOf { it.vatAmount }).setScale(2, RoundingMode.HALF_UP)
        val grandTotal = subtotalAll.add(totalVat).setScale(2, RoundingMode.HALF_UP)

        return BillingContext(
            profile = profile,
            storageLines = storageLines,
            movementLines = movementLines,
            serviceLines = serviceLines,
            subtotal = subtotalAll,
            totalVat = totalVat,
            grandTotal = grandTotal,
            minimumChargeApplied = minimumApplied,
            warnings = warnings
        )
    }

    private fun resolveStorageRate(profile: CustomerBillingProfile, projectCode: String?): Pair<BigDecimal, String?> {
        if (projectCode == null) return profile.defaultCbmRatePerDay to null
        val project = profile.projects.firstOrNull { it.projectCode == projectCode && it.isActive }
        return if (project != null) project.cbmRatePerDay to project.label
        else profile.defaultCbmRatePerDay to null
    }

    private fun resolveInboundRate(profile: CustomerBillingProfile, projectCode: String?): Pair<BigDecimal?, String?> {
        if (projectCode == null) return profile.defaultInboundCbmRate to null
        val project = profile.projects.firstOrNull { it.projectCode == projectCode && it.isActive }
        val rate = project?.inboundCbmRate ?: profile.defaultInboundCbmRate
        return rate to project?.label
    }

    private fun resolveOutboundRate(profile: CustomerBillingProfile, projectCode: String?): Pair<BigDecimal?, String?> {
        if (projectCode == null) return profile.defaultOutboundCbmRate to null
        val project = profile.projects.firstOrNull { it.projectCode == projectCode && it.isActive }
        val rate = project?.outboundCbmRate ?: profile.defaultOutboundCbmRate
        return rate to project?.label
    }

    private fun formatMonth(ym: YearMonth): String =
        ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

    private fun emptyPreview(
        customerId: Long,
        billingMonth: String,
        alreadyGenerated: Boolean,
        existingInvoiceId: String?,
        blocker: String
    ): BillingPreviewResponse = BillingPreviewResponse(
        customerId = customerId,
        billingMonth = billingMonth,
        storageLines = emptyList(),
        movementLines = emptyList(),
        serviceLines = emptyList(),
        subtotal = BigDecimal.ZERO,
        totalVat = BigDecimal.ZERO,
        grandTotal = BigDecimal.ZERO,
        minimumChargeApplied = null,
        dataQualityWarnings = listOf(
            DataQualityWarning(WarningSeverity.BLOCKER, "NO_PROFILE", blocker)
        ),
        canGenerate = false,
        alreadyGenerated = alreadyGenerated,
        existingInvoiceId = existingInvoiceId
    )
}

private data class BillingContext(
    val profile: CustomerBillingProfile,
    val storageLines: List<StorageLine>,
    val movementLines: List<MovementLine>,
    val serviceLines: List<ServiceLine>,
    val subtotal: BigDecimal,
    val totalVat: BigDecimal,
    val grandTotal: BigDecimal,
    val minimumChargeApplied: BigDecimal?,
    val warnings: List<DataQualityWarning>
) {
    fun toFreighAiLineItems(): List<FreighAiInvoiceLineItem> {
        val out = mutableListOf<FreighAiInvoiceLineItem>()
        // Storage first (excluding minimum top-up), then minimum top-up if any,
        // then movement, then services. Mirrors the worked example in the plan.
        storageLines.filter { !it.isMinimumTopUp }.forEach {
            out += FreighAiInvoiceLineItem(
                description = it.description,
                quantity = it.cbmDays,
                unit = "CBM-day",
                unitPrice = it.ratePerDay,
                chargeTypeId = it.freighaiChargeTypeId
            )
        }
        storageLines.filter { it.isMinimumTopUp }.forEach {
            out += FreighAiInvoiceLineItem(
                description = it.description,
                quantity = BigDecimal.ONE,
                unit = "topup",
                unitPrice = it.amount,
                chargeTypeId = it.freighaiChargeTypeId
            )
        }
        movementLines.filter { it.direction == MovementDirection.INBOUND }.forEach {
            out += FreighAiInvoiceLineItem(
                description = it.description,
                quantity = it.totalCbm,
                unit = "CBM",
                unitPrice = it.ratePerCbm,
                chargeTypeId = it.freighaiChargeTypeId
            )
        }
        movementLines.filter { it.direction == MovementDirection.OUTBOUND }.forEach {
            out += FreighAiInvoiceLineItem(
                description = it.description,
                quantity = it.totalCbm,
                unit = "CBM",
                unitPrice = it.ratePerCbm,
                chargeTypeId = it.freighaiChargeTypeId
            )
        }
        serviceLines.forEach {
            out += FreighAiInvoiceLineItem(
                description = it.description,
                quantity = it.quantity,
                unit = it.unit,
                unitPrice = it.ratePerUnit,
                chargeTypeId = it.freighaiChargeTypeId
            )
        }
        return out
    }
}
