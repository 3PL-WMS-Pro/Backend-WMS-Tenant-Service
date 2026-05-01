package com.wmspro.tenant.billing.profile

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface CustomerBillingProfileRepository : MongoRepository<CustomerBillingProfile, Long> {
    fun findByBillingEnabled(billingEnabled: Boolean): List<CustomerBillingProfile>
}
