package com.wmspro.tenant.billing.invoice

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface BillingRunSummaryRepository : MongoRepository<BillingRunSummary, String> {
    fun findAllByOrderByTriggeredAtDesc(pageable: Pageable): Page<BillingRunSummary>
    fun findByBillingMonthOrderByTriggeredAtDesc(billingMonth: String): List<BillingRunSummary>
}
