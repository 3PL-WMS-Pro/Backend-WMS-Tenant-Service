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