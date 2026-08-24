package com.wmspro.tenant.billing.warehousejob.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.adjustment.MovementCostAdjustmentService
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class WarehouseJobCutoverConflict(message: String) : IllegalStateException(message)

class WarehouseJobFreezeCompensationException(
    message: String,
    cause: Throwable,
    val compensationFailures: List<String>
) : IllegalStateException(message, cause)

data class FrozenWarehouseJobRun(
    val invoice: WmsBillingInvoice,
    val snapshots: List<BillingRunCostSnapshot>,
    val outbox: List<WarehouseJobOutbox>
)

@Service
class WarehouseJobFreezeService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val snapshotRepository: BillingRunCostSnapshotRepository,
    private val outboxRepository: WarehouseJobOutboxRepository,
    private val payloadBuilder: WarehouseJobPayloadBuilder,
    private val movementCostAdjustmentService: MovementCostAdjustmentService,
    private val claimIntentService: WarehouseJobClaimIntentService,
    private val objectMapper: ObjectMapper
) {
    /**
     * Standalone-safe local freeze for a previously nonexistent tuple.
     *
     * Production WMS Mongo is intentionally standalone, like the established
     * billing path. We therefore insert deterministic, fenced records in order
     * and compensate only the records inserted by this attempt if any step
     * fails. Remote delivery remains impossible until the claim intent reaches
     * READY, so a partial local freeze can never escape to FreighAI.
     */
    fun freezeNewTuple(
        tenantId: String,
        candidateInvoice: WmsBillingInvoice,
        candidateSnapshots: List<BillingRunCostSnapshot>,
        movementAdjustmentIds: List<String>,
        customerName: String?,
        freighAiCustomerId: String,
        currencyId: String,
        correlationId: String
    ): FrozenWarehouseJobRun {
        check(TenantContext.getCurrentTenant() == tenantId && MongoConnectionStorage.hasExplicitConnection()) {
            "Warehouse Job freeze requires matching explicit tenant and Mongo connection contexts"
        }
        if (candidateInvoice.generationContractVersion != null) {
            throw WarehouseJobCutoverConflict("Candidate must be unpersisted and unmarked")
        }
        if (invoiceRepository.existsById(candidateInvoice.billingInvoiceId)) {
            throw WarehouseJobCutoverConflict(
                "CUTOVER_CONFLICT: candidate billingInvoiceId already exists; V1 never adopts or rewrites a stored header"
            )
        }
        require(candidateSnapshots.map { it.snapshotId }.distinct().size == candidateSnapshots.size) {
            "CUTOVER_CONFLICT: duplicate snapshot identity in V1 candidate"
        }
        val existingSnapshotId = candidateSnapshots.firstOrNull { snapshotRepository.existsById(it.snapshotId) }?.snapshotId
        if (existingSnapshotId != null) {
            throw WarehouseJobCutoverConflict(
                "CUTOVER_CONFLICT: snapshot '$existingSnapshotId' already exists; V1 never adopts or rewrites stored cost evidence"
            )
        }
        val existing = invoiceRepository.findByCustomerIdAndProjectCodeAndBillingMonth(
            candidateInvoice.customerId,
            candidateInvoice.projectCode,
            candidateInvoice.billingMonth
        )
        if (existing != null) {
            throw WarehouseJobCutoverConflict(
                "CUTOVER_CONFLICT: billing tuple already exists and remains ${existing.generationContractVersion ?: "legacy"}"
            )
        }

        val payloadVersion = 1L
        val externalReference = "WMS-${candidateInvoice.customerId}-${candidateInvoice.projectCode ?: "default"}-${candidateInvoice.billingMonth}"
        val markedSnapshots = candidateSnapshots.map { snapshot ->
            require(snapshot.billingInvoiceId == candidateInvoice.billingInvoiceId) { "Snapshot belongs to another invoice" }
            require(!snapshot.costTreatment.isNullOrBlank()) { "V1 snapshot requires explicit costTreatment" }
            val costLineId = snapshot.costLineId ?: payloadBuilder.stableCostLineId(
                snapshot.sourceType.name,
                snapshot.sourceRecord.id,
                snapshot.sourceLineId
            )
            snapshot.copy(
                snapshotId = "costsnap_${stableKey(candidateInvoice.billingInvoiceId, costLineId).take(24)}",
                generationContractVersion = WarehouseJobGenerationContracts.V1,
                costLineId = costLineId,
                calculationVersion = snapshot.calculationVersion ?: "WMS_COST_V1",
                completionWeight = snapshot.completionWeight
                    ?: snapshot.totalCost?.abs()?.takeIf { it.signum() != 0 }
                    ?: java.math.BigDecimal.ONE
            )
        }
        var markedInvoice = candidateInvoice.copy(
            generationContractVersion = WarehouseJobGenerationContracts.V1,
            warehouseJobExternalReference = externalReference,
            warehouseJobPayloadVersion = payloadVersion,
            warehouseJobSyncState = WarehouseJobSyncState.PENDING
        )
        val built = payloadBuilder.build(
            tenantId, markedInvoice, markedSnapshots, customerName, currencyId, payloadVersion, freighAiCustomerId
        )
        markedInvoice = markedInvoice.copy(
            warehouseJobPayloadHash = built.sha256,
            warehouseJobCurrencyCode = built.request.commercialSnapshot.currencyCode,
            warehouseJobCustomerName = built.request.customerSnapshot.customerName
        )

        val wjIdempotencyKey = stableKey(tenantId, candidateInvoice.billingInvoiceId, payloadVersion.toString(), "UPSERT_WJ")
        val wjOutboxId = "wj-${stableKey(candidateInvoice.billingInvoiceId, payloadVersion.toString()).take(24)}"
        val wjCommand = WarehouseJobOutbox(
            outboxId = wjOutboxId,
            billingInvoiceId = candidateInvoice.billingInvoiceId,
            payloadVersion = payloadVersion,
            operation = WarehouseJobOutboxOperation.UPSERT_WJ,
            idempotencyKey = wjIdempotencyKey,
            payload = built.canonicalJson,
            payloadHash = built.sha256,
            correlationId = correlationId
        )
        val siPayload = objectMapper.writeValueAsString(
            sortedMapOf(
                "billingInvoiceId" to candidateInvoice.billingInvoiceId,
                "externalReference" to "WMS-SI-${candidateInvoice.billingInvoiceId}-V$payloadVersion",
                "generationContractVersion" to WarehouseJobGenerationContracts.V1,
                "jobLinkContractVersion" to "GENERIC_JOB_V1",
                "payloadVersion" to payloadVersion.toString()
            )
        )
        val siCommand = WarehouseJobOutbox(
            outboxId = "si-${stableKey(candidateInvoice.billingInvoiceId, payloadVersion.toString()).take(24)}",
            billingInvoiceId = candidateInvoice.billingInvoiceId,
            payloadVersion = payloadVersion,
            operation = WarehouseJobOutboxOperation.CREATE_OR_LINK_SI,
            idempotencyKey = stableKey(tenantId, candidateInvoice.billingInvoiceId, payloadVersion.toString(), "CREATE_OR_LINK_SI"),
            dependencyOutboxId = wjOutboxId,
            payload = siPayload,
            payloadHash = sha256(siPayload),
            correlationId = correlationId
        )

        var invoiceInserted = false
        val insertedSnapshotIds = mutableListOf<String>()
        val insertedOutboxIds = mutableListOf<String>()
        var adjustmentsLocked = false
        try {
            val savedInvoice = invoiceRepository.insert(markedInvoice).also { invoiceInserted = true }
            val savedSnapshots = markedSnapshots.map { snapshot ->
                snapshotRepository.insert(snapshot).also { insertedSnapshotIds += it.snapshotId }
            }
            if (movementAdjustmentIds.isNotEmpty()) {
                movementCostAdjustmentService.lockToBillingInvoice(movementAdjustmentIds.distinct(), candidateInvoice.billingInvoiceId)
                adjustmentsLocked = true
            }
            val commands = listOf(wjCommand, siCommand).map { command ->
                outboxRepository.insert(command).also { insertedOutboxIds += it.outboxId }
            }
            claimIntentService.markFrozen(candidateInvoice.billingInvoiceId)
            return FrozenWarehouseJobRun(savedInvoice, savedSnapshots, commands)
        } catch (e: DuplicateKeyException) {
            val failures = compensate(
                candidateInvoice.billingInvoiceId, invoiceInserted, insertedSnapshotIds,
                insertedOutboxIds, adjustmentsLocked
            )
            if (failures.isNotEmpty()) {
                throw WarehouseJobFreezeCompensationException(
                    "Concurrent V1 freeze failed and local compensation is incomplete: ${failures.joinToString()}",
                    e,
                    failures
                )
            }
            throw WarehouseJobCutoverConflict("CUTOVER_CONFLICT: concurrent billing tuple or outbox identity already exists")
        } catch (e: Exception) {
            val failures = compensate(
                candidateInvoice.billingInvoiceId, invoiceInserted, insertedSnapshotIds,
                insertedOutboxIds, adjustmentsLocked
            )
            if (failures.isNotEmpty()) {
                throw WarehouseJobFreezeCompensationException(
                    "Local V1 freeze failed and compensation is incomplete: ${failures.joinToString()}",
                    e,
                    failures
                )
            }
            throw e
        }
    }

    fun current(billingInvoiceId: String): WmsBillingInvoice? =
        invoiceRepository.findById(billingInvoiceId).orElse(null)

    private fun compensate(
        billingInvoiceId: String,
        invoiceInserted: Boolean,
        snapshotIds: List<String>,
        outboxIds: List<String>,
        adjustmentsLocked: Boolean
    ): List<String> {
        val failures = mutableListOf<String>()
        outboxIds.asReversed().forEach { id ->
            runCatching { outboxRepository.deleteById(id) }
                .onFailure { failures += "outbox:$id:${it.message}" }
        }
        if (adjustmentsLocked) {
            runCatching { movementCostAdjustmentService.unlockFromBillingInvoice(billingInvoiceId) }
                .onFailure { failures += "adjustments:$billingInvoiceId:${it.message}" }
        }
        snapshotIds.asReversed().forEach { id ->
            runCatching { snapshotRepository.deleteById(id) }
                .onFailure { failures += "snapshot:$id:${it.message}" }
        }
        if (invoiceInserted) {
            runCatching { invoiceRepository.deleteById(billingInvoiceId) }
                .onFailure { failures += "invoice:$billingInvoiceId:${it.message}" }
        }
        return failures
    }

    private fun stableKey(vararg parts: String): String = sha256(parts.joinToString("|")).take(48)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
