package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.billing.BillingClaimOrphanDecision
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import org.springframework.stereotype.Service

data class OrphanClaimDecisionResponse(
    val decision: BillingClaimOrphanDecision,
    val reason: String
)

@Service
class WarehouseJobClaimOrphanDecisionService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val snapshotRepository: BillingRunCostSnapshotRepository,
    private val outboxRepository: WarehouseJobOutboxRepository
) {
    /** Read-only and V1-scoped; callers perform a fenced transition locally. */
    fun decide(billingInvoiceId: String): OrphanClaimDecisionResponse {
        val commands = outboxRepository.findByGenerationContractVersionAndBillingInvoiceId(
            WarehouseJobGenerationContracts.V1,
            billingInvoiceId
        )
        val invoice = invoiceRepository.findById(billingInvoiceId).orElse(null)
        if (invoice == null) {
            return if (commands.any { it.attempts > 0 || it.state == WarehouseJobOutboxState.SUCCEEDED }) {
                OrphanClaimDecisionResponse(BillingClaimOrphanDecision.MANUAL_REVIEW, "Remote side effect is possible but owner header is missing")
            } else {
                OrphanClaimDecisionResponse(BillingClaimOrphanDecision.RELEASE, "No durable V1 owner exists")
            }
        }
        if (invoice.generationContractVersion != WarehouseJobGenerationContracts.V1) {
            return OrphanClaimDecisionResponse(BillingClaimOrphanDecision.MANUAL_REVIEW, "CUTOVER_CONFLICT: owner is not V1")
        }
        val possibleRemoteEffect = invoice.warehouseJobId != null || invoice.freighaiInvoiceId != null ||
            commands.any { it.attempts > 0 || it.state in setOf(WarehouseJobOutboxState.LEASED, WarehouseJobOutboxState.SUCCEEDED) }
        val completeLocalOwner = snapshotRepository.existsByBillingInvoiceIdAndGenerationContractVersion(
            billingInvoiceId,
            WarehouseJobGenerationContracts.V1
        ) && commands.isNotEmpty()
        return when {
            completeLocalOwner -> OrphanClaimDecisionResponse(BillingClaimOrphanDecision.COMMIT, "Durable V1 header, snapshots and outbox exist")
            possibleRemoteEffect -> OrphanClaimDecisionResponse(BillingClaimOrphanDecision.MANUAL_REVIEW, "Remote side effect is possible")
            else -> OrphanClaimDecisionResponse(BillingClaimOrphanDecision.RELEASE, "Incomplete local owner and no remote side effect")
        }
    }
}
