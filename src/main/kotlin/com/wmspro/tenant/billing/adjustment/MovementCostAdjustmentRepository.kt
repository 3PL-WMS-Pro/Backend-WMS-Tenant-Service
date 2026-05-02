package com.wmspro.tenant.billing.adjustment

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface MovementCostAdjustmentRepository : MongoRepository<MovementCostAdjustment, String> {

    /** All adjustments for one source record (GRN or GIN). Drives the panel UI. */
    fun findByAttachedToId(attachedToId: String): List<MovementCostAdjustment>

    /**
     * Unbilled adjustments — used by billing run to attach adjustments to
     * the cost snapshot. Filters out anything already locked to a SUBMITTED
     * invoice.
     */
    fun findByAttachedToIdAndBillingInvoiceIdIsNull(attachedToId: String): List<MovementCostAdjustment>

    /** Cross-record list for a (customer, date range). */
    fun findByCustomerIdAndCreatedAtBetween(
        customerId: Long,
        from: Instant,
        to: Instant
    ): List<MovementCostAdjustment>

    /** Used by billing-run cancel to clear billingInvoiceId locks. */
    fun findByBillingInvoiceId(billingInvoiceId: String): List<MovementCostAdjustment>
}
