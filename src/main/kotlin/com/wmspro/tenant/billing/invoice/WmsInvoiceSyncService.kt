package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import org.slf4j.LoggerFactory
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
    private val freighAiInvoiceClient: FreighAiInvoiceClient
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
                val changed = inv.freighaiStatus != freighai.currentStatus
                    || inv.freighaiInvoiceDate != freighai.invoiceDate
                    || inv.freighaiDueDate != freighai.dueDate
                    || inv.freighaiOutstandingAmount != freighai.outstandingAmount
                invoiceRepository.save(
                    inv.copy(
                        freighaiStatus = freighai.currentStatus,
                        freighaiInvoiceDate = freighai.invoiceDate,
                        freighaiDueDate = freighai.dueDate,
                        freighaiOutstandingAmount = freighai.outstandingAmount,
                        lastSyncedAt = Instant.now()
                    )
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
