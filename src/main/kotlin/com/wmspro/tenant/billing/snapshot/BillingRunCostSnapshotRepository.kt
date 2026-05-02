package com.wmspro.tenant.billing.snapshot

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface BillingRunCostSnapshotRepository : MongoRepository<BillingRunCostSnapshot, String> {

    /** All snapshots for one billing invoice — used for reconciliation report join + cancellation cleanup. */
    fun findByBillingInvoiceId(billingInvoiceId: String): List<BillingRunCostSnapshot>

    /** Cross-record list for a (customer, month) — reconciliation report. */
    fun findByCustomerIdAndBillingMonth(customerId: Long, billingMonth: String): List<BillingRunCostSnapshot>

    /** Tenant-wide view for one month — tenant-rollup reconciliation. */
    fun findByBillingMonth(billingMonth: String): List<BillingRunCostSnapshot>

    /** Cancellation cleanup — remove all snapshots tied to a cancelled invoice. */
    fun deleteByBillingInvoiceId(billingInvoiceId: String): Long
}
