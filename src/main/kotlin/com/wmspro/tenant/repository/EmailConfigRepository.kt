package com.wmspro.tenant.repository

import com.wmspro.tenant.model.EmailConfig
import com.wmspro.tenant.model.EmailConfigStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for EmailConfig operations
 */
@Repository
interface EmailConfigRepository : MongoRepository<EmailConfig, String> {

    /**
     * Find all email configs for a specific tenant
     */
    fun findByTenantId(tenantId: Int): List<EmailConfig>

    /**
     * Find email config by ID and tenant ID
     */
    fun findByIdAndTenantId(id: String, tenantId: Int): Optional<EmailConfig>

    /**
     * Find by connection name and tenant ID
     */
    fun findByConnectionNameAndTenantId(connectionName: String, tenantId: Int): Optional<EmailConfig>

    /**
     * Find all active email configs for a tenant
     */
    fun findByTenantIdAndStatus(tenantId: Int, status: EmailConfigStatus): List<EmailConfig>

    /**
     * Check if connection name exists for a tenant
     */
    fun existsByConnectionNameAndTenantId(connectionName: String, tenantId: Int): Boolean

    /**
     * Delete by ID and tenant ID
     */
    fun deleteByIdAndTenantId(id: String, tenantId: Int): Long
}
