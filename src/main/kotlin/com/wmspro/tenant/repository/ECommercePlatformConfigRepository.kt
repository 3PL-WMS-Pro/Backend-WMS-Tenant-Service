package com.wmspro.tenant.repository

import com.wmspro.tenant.model.ECommercePlatformConfig
import com.wmspro.tenant.model.ECommercePlatformConfigStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for ECommercePlatformConfig entity
 */
@Repository
interface ECommercePlatformConfigRepository : MongoRepository<ECommercePlatformConfig, String> {

    /**
     * Find all e-commerce platform configs for a specific tenant
     */
    fun findByTenantId(tenantId: Int): List<ECommercePlatformConfig>

    /**
     * Find e-commerce platform config by ID and tenant ID (for tenant isolation)
     */
    fun findByIdAndTenantId(id: String, tenantId: Int): Optional<ECommercePlatformConfig>

    /**
     * Find e-commerce platform configs by tenant ID and status
     */
    fun findByTenantIdAndStatus(tenantId: Int, status: ECommercePlatformConfigStatus): List<ECommercePlatformConfig>

    /**
     * Find e-commerce platform configs by tenant ID and platform type
     */
    fun findByTenantIdAndPlatformType(tenantId: Int, platformType: String): List<ECommercePlatformConfig>

    /**
     * Delete e-commerce platform config by ID and tenant ID (for tenant isolation)
     */
    fun deleteByIdAndTenantId(id: String, tenantId: Int): Long
}
