package com.wmspro.tenant.repository

import com.wmspro.tenant.model.TenantDatabaseMapping
import com.wmspro.tenant.model.TenantStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for TenantDatabaseMapping model
 * This repository always operates on the central database
 */
@Repository
interface TenantDatabaseMappingRepository : MongoRepository<TenantDatabaseMapping, Int> {

    /**
     * Finds a tenant by client ID
     */
    fun findByClientId(clientId: Int): Optional<TenantDatabaseMapping>

    /**
     * Finds all active tenants
     */
    fun findByStatus(status: TenantStatus): List<TenantDatabaseMapping>

    /**
     * Finds tenants by status list
     */
    fun findByStatusIn(statuses: List<TenantStatus>): List<TenantDatabaseMapping>

    /**
     * Checks if a tenant exists by client ID
     */
    fun existsByClientId(clientId: Int): Boolean

    /**
     * Finds tenants using a specific MongoDB database
     */
    @Query("{'mongoConnection.databaseName': ?0}")
    fun findByDatabaseName(databaseName: String): List<TenantDatabaseMapping>

    /**
     * Finds tenants by S3 bucket name
     */
    @Query("{'s3Configuration.bucketName': ?0}")
    fun findByS3BucketName(bucketName: String): List<TenantDatabaseMapping>

    /**
     * Counts active tenants
     */
    fun countByStatus(status: TenantStatus): Long

    /**
     * Deletes a tenant by client ID
     */
    fun deleteByClientId(clientId: Int): Long
}