package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.ReserveBillingSourceClaimRequest
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.cascade.BillingClaimTarget
import com.wmspro.tenant.billing.invoice.cascade.ClaimedBillingSource
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Forward-only V1 generation boundary. Once an intent is inserted this method
 * can only converge the V1 claim/freeze path; it never calls legacy locks or
 * the legacy synchronous Finance writer.
 */
@Service
class WarehouseJobGenerationService(
    private val intentService: WarehouseJobClaimIntentService,
    private val cascadeClient: WmsInternalCascadeClient,
    private val freezeService: WarehouseJobFreezeService,
    private val payloadBuilder: WarehouseJobPayloadBuilder,
    private val outboxAdminService: WarehouseJobOutboxAdminService,
    private val outboxWorker: WarehouseJobOutboxWorker
) {
    fun generateNewTuple(
        candidateInvoice: WmsBillingInvoice,
        candidateSnapshots: List<BillingRunCostSnapshot>,
        movementAdjustmentIds: List<String>,
        customerName: String,
        freighAiCustomerId: String,
        currencyId: String,
        authToken: String,
        correlationId: String
    ): WmsBillingInvoice {
        val tenantId = requireNotNull(TenantContext.getCurrentTenant()?.takeIf(String::isNotBlank)) {
            "Warehouse Job generation requires an explicit tenant context"
        }
        val payloadVersion = 1L
        val claimOwnerKey = "${candidateInvoice.billingInvoiceId}:$payloadVersion"
        val claimTargets = targets(candidateSnapshots)
        val fingerprint = sha256(claimTargets.joinToString("|") { "${it.first}:${it.second.id}:${it.second.sourceLineId}" })
        val expiresAt = Instant.now().plus(Duration.ofMinutes(15))
        intentService.createOrAdopt(
            WarehouseJobClaimIntent(
                billingInvoiceId = candidateInvoice.billingInvoiceId,
                payloadVersion = payloadVersion,
                customerId = candidateInvoice.customerId,
                projectBucket = candidateInvoice.projectCode ?: "DEFAULT",
                billingMonth = candidateInvoice.billingMonth,
                sourceFingerprint = fingerprint,
                expiresAt = expiresAt
            )
        )

        val grouped = claimTargets.groupBy({ it.first }, { it.second })
        val reserve = cascadeClient.reserveClaims(
            receivingRecords = grouped[SnapshotSourceType.INBOUND].orEmpty(),
            fulfillmentRecords = grouped[SnapshotSourceType.OUTBOUND].orEmpty(),
            serviceLogs = grouped[SnapshotSourceType.SERVICE].orEmpty(),
            requestFor = { target ->
                ReserveBillingSourceClaimRequest(
                    billingInvoiceId = candidateInvoice.billingInvoiceId,
                    claimOwnerKey = claimOwnerKey,
                    sourceLineId = target.sourceLineId,
                    generationContractVersion = WarehouseJobGenerationContracts.V1,
                    payloadVersion = payloadVersion,
                    accountingPeriod = candidateInvoice.billingMonth,
                    claimVersion = 1,
                    expiresAt = expiresAt
                )
            },
            authToken = authToken
        )
        if (!reserve.isAllSuccess(claimTargets.size)) {
            val releaseFailures = release(reserve.reserved, claimOwnerKey, authToken)
            val error = "Source reservation failed: ${reserve.failures.joinToString()}; release failures: ${releaseFailures.joinToString()}"
            if (releaseFailures.isEmpty()) intentService.markReleased(candidateInvoice.billingInvoiceId, error)
            else intentService.markManualReview(candidateInvoice.billingInvoiceId, error)
            throw WarehouseJobCutoverConflict("CUTOVER_CONFLICT: $error")
        }

        val frozen = try {
            freezeService.freezeNewTuple(
                tenantId = tenantId,
                candidateInvoice = candidateInvoice,
                candidateSnapshots = candidateSnapshots,
                movementAdjustmentIds = movementAdjustmentIds,
                customerName = customerName,
                freighAiCustomerId = freighAiCustomerId,
                currencyId = currencyId,
                correlationId = correlationId
            )
        } catch (failure: Exception) {
            if (failure is WarehouseJobFreezeCompensationException) {
                val error = "Local freeze compensation is incomplete: ${failure.compensationFailures.joinToString()}"
                intentService.markManualReview(candidateInvoice.billingInvoiceId, error)
                outboxAdminService.quarantine(candidateInvoice.billingInvoiceId, error)
                // Do not release reserved sources: a partial durable owner may
                // remain and releasing would allow the same work to be billed
                // again while that evidence is under manual repair.
                throw failure
            }
            val releaseFailures = release(reserve.reserved, claimOwnerKey, authToken)
            val error = "Local freeze failed: ${failure.message}; release failures: ${releaseFailures.joinToString()}"
            if (releaseFailures.isEmpty()) intentService.markReleased(candidateInvoice.billingInvoiceId, error)
            else intentService.markManualReview(candidateInvoice.billingInvoiceId, error)
            throw failure
        }

        val commitFailures = cascadeClient.transitionClaims(
            reserve.reserved,
            TransitionBillingSourceClaimRequest(claimOwnerKey, 1, WarehouseJobGenerationContracts.V1),
            commit = true,
            authToken = authToken
        )
        if (commitFailures.isNotEmpty()) {
            val error = "Frozen locally but source-claim commit is unconfirmed: ${commitFailures.joinToString()}"
            intentService.markManualReview(candidateInvoice.billingInvoiceId, error)
            outboxAdminService.quarantine(candidateInvoice.billingInvoiceId, error)
            throw IllegalStateException(error)
        }
        intentService.markReady(candidateInvoice.billingInvoiceId)
        // Manual billing follows the proven legacy authentication pattern: the
        // initiating FreighAI JWT is used immediately. The durable outbox still
        // provides exact adoption/retry if either remote response is uncertain.
        outboxWorker.drainInvoice(
            workerId = "wms-request-$correlationId",
            billingInvoiceId = candidateInvoice.billingInvoiceId,
            authToken = authToken
        )
        return freezeService.current(candidateInvoice.billingInvoiceId) ?: frozen.invoice
    }

    private fun targets(snapshots: List<BillingRunCostSnapshot>): List<Pair<SnapshotSourceType, BillingClaimTarget>> =
        snapshots.asSequence()
            .filter { it.sourceType != SnapshotSourceType.STORAGE }
            .map { snapshot ->
                val sourceLineId = snapshot.sourceLineId ?: payloadBuilder.stableCostLineId(
                    snapshot.sourceType.name,
                    snapshot.sourceRecord.id,
                    null
                )
                snapshot.sourceType to BillingClaimTarget(snapshot.sourceRecord.id, sourceLineId)
            }
            .distinctBy { "${it.first}:${it.second.id}" }
            .sortedBy { "${it.first}:${it.second.id}" }
            .toList()

    private fun release(claims: List<ClaimedBillingSource>, owner: String, authToken: String): List<String> =
        cascadeClient.transitionClaims(
            claims,
            TransitionBillingSourceClaimRequest(owner, 1, WarehouseJobGenerationContracts.V1),
            commit = false,
            authToken = authToken
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
