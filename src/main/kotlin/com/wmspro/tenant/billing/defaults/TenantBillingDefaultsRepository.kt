package com.wmspro.tenant.billing.defaults

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface TenantBillingDefaultsRepository : MongoRepository<TenantBillingDefaults, String>
