package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Hourly background sync of cached FreighAi status fields on every
 * SUBMITTED WmsBillingInvoice that's not yet final on FreighAi (PAID /
 * CANCELLED) and either never synced or stale.
 *
 * Per-tenant: iterates all tenant DBs (via TenantDatabaseMappingRepository),
 * sets TenantContext, and runs the refresh loop. The sync uses a
 * service-account JWT — TODO Phase 10 will plumb a real one; for now this
 * cron is a no-op when no service-account auth is configured (logged at
 * INFO so admin sees it). Manual /sync endpoint on the controller works
 * with admin JWT in the meantime.
 */
@Service
class WmsInvoiceSyncService(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val tenantDatabaseMappingRepository: TenantDatabaseMappingRepository,
    private val freighAiInvoiceClient: FreighAiInvoiceClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** How stale "stale" means — anything older than this gets refreshed. */
    private val staleThreshold: Duration = Duration.ofHours(1)

    @Value("\${app.external-api.freighai.service-account-jwt:}")
    private lateinit var serviceAccountJwt: String

    /**
     * Hourly cron. Top-of-hour. Skips entirely when no service-account JWT
     * is configured (early bootstrap before Phase 10 wires it in).
     */
    @Scheduled(cron = "0 0 * * * *")
    fun hourlySync() {
        if (serviceAccountJwt.isBlank()) {
            logger.info("WmsInvoiceSyncService: no service-account JWT configured; skipping hourly sync")
            return
        }
        val tenants = tenantDatabaseMappingRepository.findAll()
        logger.info("WmsInvoiceSyncService: starting hourly sync across {} tenants", tenants.size)
        for (tenant in tenants) {
            try {
                TenantContext.setCurrentTenant(tenant.clientId.toString())
                syncOneTenant()
            } catch (e: Exception) {
                logger.error("WmsInvoiceSyncService: tenant {} sync failed", tenant.clientId, e)
            } finally {
                TenantContext.clear()
            }
        }
    }

    private fun syncOneTenant() {
        val staleBefore = Instant.now().minus(staleThreshold)
        val targets = invoiceRepository.findStaleSubmittedInvoices(staleBefore)
        if (targets.isEmpty()) return
        logger.info("WmsInvoiceSyncService: refreshing {} stale invoice(s)", targets.size)

        for (inv in targets) {
            val freighaiInvoiceId = inv.freighaiInvoiceId ?: continue
            try {
                val freighai = freighAiInvoiceClient.getInvoice(freighaiInvoiceId, serviceAccountJwt)
                if (freighai == null) continue
                invoiceRepository.save(
                    inv.copy(
                        freighaiStatus = freighai.currentStatus,
                        freighaiInvoiceDate = freighai.invoiceDate,
                        freighaiDueDate = freighai.dueDate,
                        freighaiOutstandingAmount = freighai.outstandingAmount,
                        lastSyncedAt = Instant.now()
                    )
                )
            } catch (e: Exception) {
                logger.warn("WmsInvoiceSyncService: refresh failed for invoice {}", inv.billingInvoiceId, e)
            }
        }
    }
}
