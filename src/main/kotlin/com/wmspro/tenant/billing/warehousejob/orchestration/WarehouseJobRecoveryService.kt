package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.invoice.cascade.BillingClaimTarget
import com.wmspro.tenant.billing.invoice.cascade.BillingSourceKind
import com.wmspro.tenant.billing.invoice.cascade.ClaimedBillingSource
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WarehouseJobRecoveryService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val snapshotRepository: BillingRunCostSnapshotRepository,
    private val intentRepository: WarehouseJobClaimIntentRepository,
    private val intentService: WarehouseJobClaimIntentService,
    private val cascadeClient: WmsInternalCascadeClient,
    private val payloadBuilder: WarehouseJobPayloadBuilder,
    private val outboxAdminService: WarehouseJobOutboxAdminService,
    private val mongoTemplate: MongoTemplate
) {
    fun retry(id: String, authToken: String): WmsBillingInvoice {
        val invoice = resolve(id)
        require(invoice.generationContractVersion == WarehouseJobGenerationContracts.V1) {
            "CUTOVER_CONFLICT: retry is available only for WAREHOUSE_JOB_V1 records"
        }
        val version = requireNotNull(invoice.warehouseJobPayloadVersion)
        val intent = intentRepository.findById(invoice.billingInvoiceId).orElseThrow {
            WarehouseJobCutoverConflict("CUTOVER_CONFLICT: V1 claim intent is missing")
        }
        if (intent.state in setOf(WarehouseJobClaimIntentState.FROZEN, WarehouseJobClaimIntentState.MANUAL_REVIEW)) {
            val claims = sourceClaims(invoice)
            val failures = cascadeClient.transitionClaims(
                claims,
                TransitionBillingSourceClaimRequest("${invoice.billingInvoiceId}:$version", 1, WarehouseJobGenerationContracts.V1),
                commit = true,
                authToken = authToken
            )
            if (failures.isNotEmpty()) {
                val error = "Source claims still cannot be confirmed: ${failures.joinToString()}"
                outboxAdminService.quarantine(invoice.billingInvoiceId, error)
                throw IllegalStateException(error)
            }
            intentService.markReady(invoice.billingInvoiceId)
        } else if (intent.state != WarehouseJobClaimIntentState.READY) {
            throw WarehouseJobCutoverConflict("CUTOVER_CONFLICT: claim intent is ${intent.state}")
        }
        val retriedCommands = outboxAdminService.retry(invoice.billingInvoiceId)
        if (retriedCommands > 0) {
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("billingInvoiceId").`is`(invoice.billingInvoiceId)
                    .and("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
                    .and("warehouseJobPayloadVersion").`is`(version)),
                Update().set("warehouseJobSyncState", WarehouseJobSyncState.PENDING)
                    .unset("warehouseJobLastError").set("warehouseJobLastSyncedAt", Instant.now()),
                WmsBillingInvoice::class.java
            )
        }
        return invoiceRepository.findById(invoice.billingInvoiceId).orElseThrow()
    }

    fun resolve(id: String): WmsBillingInvoice = invoiceRepository.findById(id).orElse(null)
        ?: invoiceRepository.findByWarehouseJobId(id)
        ?: throw IllegalArgumentException("Warehouse Job '$id' not found")

    private fun sourceClaims(invoice: WmsBillingInvoice): List<ClaimedBillingSource> =
        snapshotRepository.findByBillingInvoiceId(invoice.billingInvoiceId).asSequence()
            .filter { it.generationContractVersion == WarehouseJobGenerationContracts.V1 && it.sourceType != SnapshotSourceType.STORAGE }
            .map {
                val kind = when (it.sourceType) {
                    SnapshotSourceType.INBOUND -> BillingSourceKind.GRN
                    SnapshotSourceType.OUTBOUND -> BillingSourceKind.GIN
                    SnapshotSourceType.SERVICE -> BillingSourceKind.SERVICE_LOG
                    SnapshotSourceType.STORAGE -> error("filtered")
                }
                val sourceLine = it.sourceLineId ?: payloadBuilder.stableCostLineId(it.sourceType.name, it.sourceRecord.id)
                ClaimedBillingSource(kind, BillingClaimTarget(it.sourceRecord.id, sourceLine))
            }
            .distinctBy { "${it.kind}:${it.target.id}" }
            .toList()
}
