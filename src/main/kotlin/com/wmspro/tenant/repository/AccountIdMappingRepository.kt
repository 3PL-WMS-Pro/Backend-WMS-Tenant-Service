package com.wmspro.tenant.repository

import com.wmspro.tenant.model.AccountIdMapping
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

/**
 * Repository for AccountIdMapping (per-tenant collection account_id_mapping).
 *
 * Routing: per-tenant via TenantAwareMongoDatabaseFactory + MongoConnectionStorage
 * ThreadLocal — set by TenantInterceptor based on the X-Client header.
 */
@Repository
interface AccountIdMappingRepository : MongoRepository<AccountIdMapping, Long> {

    /**
     * Reverse lookup: given FreighAI customer IDs, return the matching mapping
     * rows. May return more rows than input IDs in the consolidation case
     * (multiple leadtorev IDs → same FreighAI ID).
     */
    fun findByFreighaiCustomerIdIn(freighaiCustomerIds: List<String>): List<AccountIdMapping>
}
