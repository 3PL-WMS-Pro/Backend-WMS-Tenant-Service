package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.external.freighai.client.*
import com.wmspro.common.external.freighai.dto.CancelFreighAiWarehouseJobRequest
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WarehouseJobCancellationService(
    private val invoices: WmsBillingInvoiceRepository,
    private val financeClient: FreighAiInvoiceClient,
    private val warehouseClient: FreighAiWarehouseJobClient,
    private val outboxAdmin: WarehouseJobOutboxAdminService,
    private val mongoTemplate: MongoTemplate
) {
    fun cancel(id: String, reason: String, actor: String, authToken: String): WmsBillingInvoice {
        require(reason.isNotBlank()) { "Cancellation reason is required" }
        val local = invoices.findById(id).orElse(null) ?: invoices.findByWarehouseJobId(id)
            ?: throw IllegalArgumentException("Warehouse Job '$id' not found")
        require(local.generationContractVersion == WarehouseJobGenerationContracts.V1) {
            "CUTOVER_CONFLICT: Warehouse cancellation is available only for V1 records"
        }
        if (local.status == BillingInvoiceStatus.CANCELLED) return local

        val financeId = local.freighaiInvoiceId ?: when (val exact = financeClient.findInvoiceByExternalReference(
            "WMS", "WMS-SI-${local.billingInvoiceId}-V${local.warehouseJobPayloadVersion}", authToken
        )) {
            is InvoiceLookupResult.Found -> exact.invoice.invoiceId
            InvoiceLookupResult.NotFound -> null
            is InvoiceLookupResult.Unavailable -> throw IllegalStateException("Sales Invoice cancellation cannot verify remote state: ${exact.errorMessage}")
        }
        financeId?.let { invoiceId ->
            when (val result = financeClient.cancelInvoice(invoiceId, reason, authToken)) {
                CancelResult.Success -> Unit
                is CancelResult.Rejected -> if (!result.errorMessage.contains("already cancelled", true)) {
                    throw IllegalStateException("Sales Invoice cannot be cancelled: ${result.errorMessage}")
                }
                is CancelResult.Unreachable -> throw IllegalStateException("Sales Invoice cancellation is indeterminate: ${result.errorMessage}")
            }
        }

        val exact = warehouseClient.findByExternalReference(
            "WMS", requireNotNull(local.warehouseJobExternalReference), authToken,
            requireNotNull(TenantContext.getCurrentTenant()) { "Tenant context is required" }
        )
        val remote = when (exact) {
            is WarehouseJobLookupResult.Found -> exact.job
            WarehouseJobLookupResult.NotFound -> null
            is WarehouseJobLookupResult.Unavailable -> throw IllegalStateException("Warehouse Job cancellation cannot verify remote state: ${exact.errorMessage}")
        }
        remote?.let {
            val jobId = remote.jobId
            if (remote.lifecycle != "CANCELLED") {
                val version = remote.version ?: throw IllegalStateException("Warehouse Job revision is unavailable")
                when (val result = warehouseClient.cancel(
                    jobId, CancelFreighAiWarehouseJobRequest(version, reason),
                    "cancel:${local.billingInvoiceId}:${local.warehouseJobPayloadVersion}", authToken
                )) {
                    is WarehouseJobMutationResult.Success -> if (result.job.lifecycle != "CANCELLED") {
                        throw IllegalStateException("Warehouse Job cancellation returned an unexpected lifecycle")
                    }
                    is WarehouseJobMutationResult.Rejected -> throw IllegalStateException("Warehouse Job cancellation rejected: ${result.errorMessage}")
                    is WarehouseJobMutationResult.Indeterminate -> {
                        val recovered = warehouseClient.findByExternalReference(
                            "WMS", requireNotNull(local.warehouseJobExternalReference), authToken,
                            requireNotNull(TenantContext.getCurrentTenant()) { "Tenant context is required" }
                        )
                        if ((recovered as? WarehouseJobLookupResult.Found)?.job?.lifecycle != "CANCELLED") {
                            throw IllegalStateException("Warehouse Job cancellation is indeterminate: ${result.errorMessage}")
                        }
                    }
                }
            }
        }

        // Source claims and frozen evidence deliberately remain committed.
        // This prevents cancelled financial work from becoming silently
        // eligible for a second bill through either V1 or legacy generation.
        outboxAdmin.cancelUndelivered(local.billingInvoiceId)
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("billingInvoiceId").`is`(local.billingInvoiceId)
                .and("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)),
            Update().set("status", BillingInvoiceStatus.CANCELLED)
                .set("warehouseJobStatus", "CANCELLED")
                .set("warehouseJobSyncState", WarehouseJobSyncState.SYNCED)
                .set("cancelledAt", Instant.now()).set("cancelledBy", actor).set("cancelReason", reason)
                .set("warehouseJobLastSyncedAt", Instant.now()).unset("warehouseJobLastError"),
            WmsBillingInvoice::class.java
        )
        return invoices.findById(local.billingInvoiceId).orElseThrow()
    }
}
