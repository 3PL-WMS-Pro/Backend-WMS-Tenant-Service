package com.wmspro.tenant.billing.invoice

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface WmsBillingInvoiceRepository : MongoRepository<WmsBillingInvoice, String> {

    /**
     * Phase G — `(customer, month)` may now span multiple invoices (one per
     * project plus optional default). Returns the full list. Callers that
     * previously used the single-result `findByCustomerIdAndBillingMonth`
     * should use this together with the (customer, project, month) lookup
     * for idempotency.
     */
    fun findAllByCustomerIdAndBillingMonth(customerId: Long, billingMonth: String): List<WmsBillingInvoice>

    /**
     * Phase G idempotency lookup. `projectCode` may be null (default bucket).
     */
    fun findByCustomerIdAndProjectCodeAndBillingMonth(
        customerId: Long,
        projectCode: String?,
        billingMonth: String
    ): WmsBillingInvoice?

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
     * Used by manual sync (Phase F): every SUBMITTED invoice not yet in a
     * final state on FreighAi, regardless of last-sync time. Invoked on
     * the WMS Invoices list mount and by the "Refresh" button.
     */
    @Query("{ 'status': 'SUBMITTED', 'freighaiStatus': { \$nin: ['PAID', 'CANCELLED'] } }")
    fun findActiveSubmittedInvoices(): List<WmsBillingInvoice>

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
