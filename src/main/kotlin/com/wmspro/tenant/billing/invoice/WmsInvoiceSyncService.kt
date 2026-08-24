package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Manual sync of cached FreighAi status fields on every SUBMITTED
 * WmsBillingInvoice that's not yet final (PAID / CANCELLED) and isn't
 * fresh enough.
 *
 * Phase F #2 — replaced the hourly cron with this manual variant. The
 * frontend calls `POST /api/v1/wms-invoices/sync-all` on list mount and
 * via the "Refresh" button, passing the user's JWT so per-tenant scoping
 * works without needing a service-account credential.
 */
@Service
class WmsInvoiceSyncService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val freighAiInvoiceClient: FreighAiInvoiceClient,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Refresh every SUBMITTED invoice in the current tenant DB whose
     * `freighaiStatus` is not in a final state. Returns counts so the
     * caller can render a friendly toast.
     */
    fun syncAllForCurrentTenant(authToken: String): SyncAllOutcome {
        val targets = invoiceRepository.findActiveSubmittedInvoices()
        if (targets.isEmpty()) return SyncAllOutcome(0, 0, 0)

        var refreshed = 0
        var unchanged = 0
        var failed = 0
        for (inv in targets) {
            val freighaiInvoiceId = inv.freighaiInvoiceId ?: continue
            try {
                val freighai = freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken)
                if (freighai == null) { failed++; continue }
                // Totals are part of the sync because an invoice can be edited
                // on either side now — in WMS, or directly in FreighAi. Without
                // this the WMS list would keep showing pre-edit amounts.
                // BigDecimal comparison is by compareTo, not equals: 100 and
                // 100.00 are the same money but not the same object.
                val totalsMoved = freighai.grandTotal != null
                    && inv.grandTotal.compareTo(freighai.grandTotal) != 0
                val warehouseLifecycle = deriveWarehouseJobLifecycle(
                    inv.generationContractVersion, inv.warehouseJobStatus, freighai.currentStatus
                )
                val changed = inv.freighaiStatus != freighai.currentStatus
                    || inv.freighaiInvoiceDate != freighai.invoiceDate
                    || inv.freighaiDueDate != freighai.dueDate
                    || inv.freighaiOutstandingAmount != freighai.outstandingAmount
                    || inv.warehouseJobStatus != warehouseLifecycle
                    || totalsMoved

                // Field-scoped $set rather than a whole-document save. `inv` was
                // read before this loop started; saving it back would roll over
                // anything written meanwhile — and since an admin can now edit an
                // invoice concurrently, that "anything" includes editHistory,
                // editedLineItems and manuallyEdited. This touches only the
                // fields sync actually owns.
                val update = Update()
                    .set("freighaiStatus", freighai.currentStatus)
                    .set("freighaiInvoiceDate", freighai.invoiceDate)
                    .set("freighaiDueDate", freighai.dueDate)
                    .set("freighaiOutstandingAmount", freighai.outstandingAmount)
                    .set("lastSyncedAt", Instant.now())
                if (inv.generationContractVersion == "WAREHOUSE_JOB_V1") {
                    update.set("warehouseJobStatus", warehouseLifecycle)
                }
                freighai.subtotal?.let { update.set("subtotal", it) }
                freighai.totalVatAmount?.let { update.set("totalVat", it) }
                freighai.grandTotal?.let { update.set("grandTotal", it) }
                mongoTemplate.updateFirst(
                    Query(Criteria.where("_id").`is`(inv.billingInvoiceId)),
                    update,
                    WmsBillingInvoice::class.java
                )
                if (changed) refreshed++ else unchanged++
            } catch (e: Exception) {
                logger.warn("syncAll: refresh failed for invoice {}", inv.billingInvoiceId, e)
                failed++
            }
        }
        logger.info("syncAll: refreshed={} unchanged={} failed={} total={}", refreshed, unchanged, failed, targets.size)
        return SyncAllOutcome(refreshed = refreshed, unchanged = unchanged, failed = failed)
    }
}

data class SyncAllOutcome(
    val refreshed: Int,
    val unchanged: Int,
    val failed: Int
) {
    val total: Int get() = refreshed + unchanged + failed
}
