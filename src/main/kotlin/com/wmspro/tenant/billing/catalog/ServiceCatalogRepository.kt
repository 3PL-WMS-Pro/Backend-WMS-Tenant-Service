package com.wmspro.tenant.billing.catalog

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface ServiceCatalogRepository : MongoRepository<ServiceCatalog, String> {
    fun findByIsActive(isActive: Boolean): List<ServiceCatalog>
}
