package com.wmspro.tenant.repository

import com.wmspro.tenant.model.S3StorageConfig
import com.wmspro.tenant.model.S3StorageConfigStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for S3StorageConfig entity
 */
@Repository
interface S3StorageConfigRepository : MongoRepository<S3StorageConfig, String> {

    /**
     * Find all S3 storage configs for a specific tenant
     */
    fun findByTenantId(tenantId: Int): List<S3StorageConfig>

    /**
     * Find S3 storage config by ID and tenant ID (for tenant isolation)
     */
    fun findByIdAndTenantId(id: String, tenantId: Int): Optional<S3StorageConfig>

    /**
     * Find S3 storage configs by tenant ID and status
     */
    fun findByTenantIdAndStatus(tenantId: Int, status: S3StorageConfigStatus): List<S3StorageConfig>

    /**
     * Check if a bucket name exists for a tenant
     */
    fun existsByBucketNameAndTenantId(bucketName: String, tenantId: Int): Boolean

    /**
     * Delete S3 storage config by ID and tenant ID (for tenant isolation)
     */
    fun deleteByIdAndTenantId(id: String, tenantId: Int): Long
}
