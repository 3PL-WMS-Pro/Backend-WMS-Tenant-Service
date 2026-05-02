package com.wmspro.tenant.billing.costs

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface TenantOperationalCostsRepository : MongoRepository<TenantOperationalCosts, String>
