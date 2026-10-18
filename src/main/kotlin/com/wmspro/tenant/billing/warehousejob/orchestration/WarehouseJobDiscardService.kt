package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.DiscardedBillingInvoices
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Discards an invoice that was generated but never sent, so its month can be generated again -
 * typically because the customer's billing settings changed after the run.
 *
 * Whatever reached FreighAI is cancelled first: a draft Sales Invoice and the Warehouse Job. FreighAI
 * refuses to cancel a Sales Invoice that has left draft, and then nothing here changes. The invoice
 * is then moved to the discarded record, which frees its customer/project/warehouse/month.
 *
 * Only an invoice that bills nothing but storage can be discarded. GRN, GIN and service charges are
 * claimed by the invoice that bills them and a claim never goes back, so regenerating would leave
 * those charges out; such an invoice has to be cancelled instead.
 */
@Service
class WarehouseJobDiscardService(
    private val invoices: WmsBillingInvoiceRepository,
    private val snapshots: BillingRunCostSnapshotRepository,
    private val cancellation: WarehouseJobCancellationService,
    private val discarded: DiscardedBillingInvoices
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun discard(billingInvoiceId: String, reason: String, actor: String, authToken: String): WmsBillingInvoice {
        require(reason.isNotBlank()) { "A reason is required to discard an invoice" }
        val invoice = invoices.findById(billingInvoiceId).orElseThrow {
            NoSuchElementException("Invoice '$billingInvoiceId' not found")
        }
        check(invoice.generationContractVersion == WarehouseJobGenerationContracts.V1) {
            "Only invoices generated through a Warehouse Job can be discarded; cancel this one instead"
        }
        check(invoice.status == BillingInvoiceStatus.DRAFT) {
            "Invoice ${invoice.freighaiReferenceNo} is ${invoice.status} - only an invoice that was never sent can be discarded"
        }
        val claimed = snapshots.findByBillingInvoiceId(billingInvoiceId)
            .count { it.sourceType !in setOf(SnapshotSourceType.STORAGE, SnapshotSourceType.SUPPLIER_EXPENSE) }
        check(claimed == 0) {
            "Invoice ${invoice.freighaiReferenceNo} bills $claimed GRN, GIN or service record(s). Those stay marked as billed, " +
                "so a regenerated invoice would leave them out - cancel it instead"
        }

        // Throws, leaving everything as it was, if FreighAI will not cancel what it holds.
        val cancelled = cancellation.cancel(billingInvoiceId, reason, actor, authToken)
        discarded.archive(cancelled)
        invoices.deleteById(billingInvoiceId)
        logger.info("Discarded unsent invoice {} ({}) by {}: {}", billingInvoiceId, invoice.freighaiReferenceNo, actor, reason)
        return cancelled
    }
}
