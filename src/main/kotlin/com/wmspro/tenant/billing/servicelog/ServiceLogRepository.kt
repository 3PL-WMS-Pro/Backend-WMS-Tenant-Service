package com.wmspro.tenant.billing.servicelog

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
interface ServiceLogRepository : MongoRepository<ServiceLog, String> {

    fun findByCustomerIdAndPerformedAtBetween(
        customerId: Long,
        from: LocalDate,
        to: LocalDate
    ): List<ServiceLog>

    /** Used by the billing engine — aggregate only logs not yet billed. */
    @Query("{ 'customerId': ?0, 'performedAt': { \$gte: ?1, \$lte: ?2 }, 'billingInvoiceId': null }")
    fun findUnbilledByCustomerAndDateRange(
        customerId: Long,
        from: LocalDate,
        to: LocalDate
    ): List<ServiceLog>

    fun findByAttachedToId(attachedToId: String): List<ServiceLog>

    fun findByBillingInvoiceId(billingInvoiceId: String): List<ServiceLog>
}
