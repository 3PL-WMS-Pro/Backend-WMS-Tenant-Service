package com.wmspro.tenant.dto

import com.wmspro.tenant.model.*
import java.time.LocalDateTime

/**
 * Minimal DTOs for Tenant Service
 * Uses models directly where possible, only creates DTOs for:
 * 1. Hiding sensitive information in responses
 * 2. Partial updates
 * 3. Summary views
 */

/**
 * For partial updates - allows updating specific fields only
 */
data class UpdateTenantRequest(
    val status: TenantStatus? = null,
    val mongoConnection: MongoConnectionConfig? = null,
    val s3Configuration: S3Configuration? = null,
    val tenantSettings: TenantSettings? = null
)

/**
 * Summary view for listing tenants - minimal information
 */
data class TenantSummary(
    val clientId: Int,
    val databaseName: String,
    val status: String,
    val lastConnected: LocalDateTime?,
    val createdAt: LocalDateTime?
)

/**
 * Extension function to convert to summary view
 */
fun TenantDatabaseMapping.toSummary(): TenantSummary {
    return TenantSummary(
        clientId = this.clientId,
        databaseName = this.mongoConnection.databaseName,
        status = this.status.name,
        lastConnected = this.lastConnected,
        createdAt = this.createdAt
    )
}

/**
 * Secure response that hides sensitive connection details
 * Only used when we need to hide MongoDB URLs and S3 keys
 */
data class SecureTenantResponse(
    val clientId: Int,
    val databaseName: String,
    val bucketName: String,
    val status: String,
    val lastConnected: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * Convert to secure response hiding sensitive data
 */
fun TenantDatabaseMapping.toSecureResponse(): SecureTenantResponse {
    return SecureTenantResponse(
        clientId = this.clientId,
        databaseName = this.mongoConnection.databaseName,
        bucketName = this.s3Configuration.bucketName,
        status = this.status.name,
        lastConnected = this.lastConnected,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

/**
 * Request DTO for API 066: Create New Tenant
 */
data class CreateTenantRequest(
    val clientId: Int,
    val tenantName: String,
    val mongoConnection: MongoConnectionConfig,
    val s3Configuration: S3Configuration,
    val tenantSettings: TenantSettings
)

/**
 * Response DTO for API 066: Create New Tenant
 * Hides sensitive connection details
 */
data class CreateTenantResponse(
    val clientId: Int,
    val databaseName: String,
    val s3Configuration: SecureS3Config,
    val tenantSettings: TenantSettings,
    val status: String,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * Secure S3 configuration for responses (without credentials)
 */
data class SecureS3Config(
    val bucketName: String,
    val region: String,
    val bucketPrefix: String?
)

/**
 * Response DTO for API 070: Get Tenant by Client ID
 */
data class TenantInfoResponse(
    val clientId: Int,
    val tenantName: String,
    val status: String,
    val databaseName: String,
    val s3Configuration: SecureS3Config,
    val tenantSettings: TenantSettings?,
    val connectionHealth: String,
    val lastConnected: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * Response DTO for API 073: Get Tenant by ID
 */
data class TenantDetailsResponse(
    val id: String,
    val clientId: Int,
    val status: String,
    val databaseName: String,
    val s3Configuration: SecureS3Config,
    val tenantSettingsSummary: TenantSettingsSummary,
    val usageStats: UsageStats?,
    val connectionHealth: String,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * Summary of tenant settings for API 073
 */
data class TenantSettingsSummary(
    val autoAssignmentStrategy: String,
    val slaSettingsConfigured: Boolean
)

/**
 * Usage statistics for API 073
 */
data class UsageStats(
    val databaseSizeMb: Double,
    val totalDocuments: Long,
    val activeUsersCount: Int,
    val storageUsedGb: Double
)

/**
 * Custom exceptions for tenant operations
 */
class DuplicateKeyException(message: String) : RuntimeException(message)
class ConnectionTestException(message: String) : RuntimeException(message)