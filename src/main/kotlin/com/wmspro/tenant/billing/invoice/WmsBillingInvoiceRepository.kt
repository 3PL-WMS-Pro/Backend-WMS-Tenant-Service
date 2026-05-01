package com.wmspro.tenant.billing.invoice

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface WmsBillingInvoiceRepository : MongoRepository<WmsBillingInvoice, String> {

    fun findByCustomerIdAndBillingMonth(customerId: Long, billingMonth: String): WmsBillingInvoice?

    fun findByFreighaiInvoiceId(freighaiInvoiceId: String): WmsBillingInvoice?

    fun findByCustomerId(customerId: Long): List<WmsBillingInvoice>

    fun findByBillingMonth(billingMonth: String): List<WmsBillingInvoice>

    /**
     * Used by the hourly status-sync cron (Phase 7): refresh only invoices
     * that are SUBMITTED on our side, not yet final on FreighAi (PAID /
     * CANCELLED), and either never synced or stale.
     */
    @Query(
        "{ 'status': 'SUBMITTED', 'freighaiStatus': { \$nin: ['PAID', 'CANCELLED'] }, " +
        "\$or: [ { 'lastSyncedAt': null }, { 'lastSyncedAt': { \$lt: ?0 } } ] }"
    )
    fun findStaleSubmittedInvoices(staleBefore: Instant): List<WmsBillingInvoice>

    /**
     * Used by ServiceLogs in Phase 5 to detect "is this month already
     * locked for this customer?" when carrying-over backdated entries.
     */
    fun existsByCustomerIdAndBillingMonthAndStatus(
        customerId: Long,
        billingMonth: String,
        status: BillingInvoiceStatus
    ): Boolean
}
