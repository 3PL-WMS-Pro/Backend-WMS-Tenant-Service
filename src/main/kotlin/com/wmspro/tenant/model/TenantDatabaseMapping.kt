package com.wmspro.tenant.model

import com.fasterxml.jackson.annotation.JsonAlias
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime
import jakarta.validation.constraints.*

/**
 * TenantDatabaseMapping Model - Maps tenants to their databases and stores tenant-specific settings
 * Collection: tenant_database_mappings
 * Database: Central shared database (NOT tenant-specific)
 */
@Document(collection = "tenant_database_mappings")
data class TenantDatabaseMapping(
    @Id
    @Indexed(unique = true)
    val clientId: Int,

    val mongoConnection: MongoConnectionConfig,

    val s3Configuration: S3Configuration,

    val status: TenantStatus = TenantStatus.ACTIVE,

    val tenantSettings: TenantSettings = TenantSettings(),

    val lastConnected: LocalDateTime? = null,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
)

/**
 * MongoDB connection configuration
 */
data class MongoConnectionConfig(
    @field:NotBlank(message = "MongoDB URL cannot be blank")
    @JsonAlias("mongodbUri", "mongoUri", "uri", "connectionUri", "connectionString")
    val url: String,

    @field:NotBlank(message = "Database name cannot be blank")
    @JsonAlias("db", "database")
    val databaseName: String,

    val connectionOptions: ConnectionOptions = ConnectionOptions()
) {
    init {
        require(url.isNotBlank()) { "MongoDB URL cannot be blank" }
        require(databaseName.isNotBlank()) { "Database name cannot be blank" }
    }
}

/**
 * MongoDB connection options
 */
data class ConnectionOptions(
    val maxPoolSize: Int = 10,
    val minPoolSize: Int = 2,
    val retryWrites: Boolean = true,
    val w: String = "majority"
) {
    init {
        require(maxPoolSize >= minPoolSize) { "Max pool size must be >= min pool size" }
        require(maxPoolSize > 0) { "Max pool size must be positive" }
        require(minPoolSize >= 0) { "Min pool size cannot be negative" }
    }
}

/**
 * S3 bucket configuration for tenant
 */
data class S3Configuration(
    @field:NotBlank(message = "Bucket name cannot be blank")
    val bucketName: String,

    @field:NotBlank(message = "Region cannot be blank")
    val region: String = "ap-south-1",

    @field:NotBlank(message = "Access key cannot be blank")
    val accessKey: String,

    @field:NotBlank(message = "Secret key cannot be blank")
    val secretKey: String,

    val bucketPrefix: String? = null
) {
    init {
        require(bucketName.isNotBlank()) { "Bucket name cannot be blank" }
        require(region.isNotBlank()) { "Region cannot be blank" }
        require(accessKey.isNotBlank()) { "Access key cannot be blank" }
        require(secretKey.isNotBlank()) { "Secret key cannot be blank" }
    }
}

/**
 * Tenant-specific settings (replaces TaskTemplate model)
 */
data class TenantSettings(
    val taskConfigurations: TaskConfigurations = TaskConfigurations(),
    val billingSettings: Map<String, Any> = emptyMap(),
    val inventorySettings: Map<String, Any> = emptyMap(),
    val orderProcessingSettings: Map<String, Any> = emptyMap(),
    val warehouseOperations: Map<String, Any> = emptyMap(),
    val integrationSettings: Map<String, Any> = emptyMap(),
    val securitySettings: Map<String, Any> = emptyMap(),
    val notificationPreferences: Map<String, Any> = emptyMap()
)

/**
 * Task management configurations
 */
data class TaskConfigurations(
    val autoAssignment: AutoAssignmentConfig = AutoAssignmentConfig(),
    val slaSettings: SlaSettings = SlaSettings(),
    val counting: Map<String, Any> = emptyMap(),
    val transfer: Map<String, Any> = emptyMap(),
    val offloading: Map<String, Any> = emptyMap(),
    val receiving: Map<String, Any> = emptyMap(),
    val putaway: Map<String, Any> = emptyMap(),
    val picking: Map<String, Any> = emptyMap(),
    val packMove: Map<String, Any> = emptyMap(),
    val pickPackMove: Map<String, Any> = emptyMap(),
    val loading: Map<String, Any> = emptyMap()
)

/**
 * Auto-assignment configuration
 */
data class AutoAssignmentConfig(
    val strategy: AssignmentStrategy = AssignmentStrategy.ROUND_ROBIN
)

/**
 * SLA (Service Level Agreement) settings for different task types
 */
data class SlaSettings(
    val countingSlaMinutes: Int = 30,
    val transferSlaMinutes: Int = 60,
    val offloadingSlaMinutes: Int = 120,
    val receivingSlaMinutes: Int = 180,
    val putawaySlaMinutes: Int = 60,
    val pickingSlaMinutes: Int = 90,
    val packMoveSlaMinutes: Int = 60,
    val pickPackMoveSlaMinutes: Int = 120,
    val loadingSlaMinutes: Int = 90,
    val escalationAfterMinutes: Int = 30
) {
    init {
        require(countingSlaMinutes > 0) { "Counting SLA must be positive" }
        require(transferSlaMinutes > 0) { "Transfer SLA must be positive" }
        require(offloadingSlaMinutes > 0) { "Offloading SLA must be positive" }
        require(receivingSlaMinutes > 0) { "Receiving SLA must be positive" }
        require(putawaySlaMinutes > 0) { "Putaway SLA must be positive" }
        require(pickingSlaMinutes > 0) { "Picking SLA must be positive" }
        require(packMoveSlaMinutes > 0) { "Pack/Move SLA must be positive" }
        require(pickPackMoveSlaMinutes > 0) { "Pick/Pack/Move SLA must be positive" }
        require(loadingSlaMinutes > 0) { "Loading SLA must be positive" }
        require(escalationAfterMinutes > 0) { "Escalation time must be positive" }
    }
}

/**
 * Assignment strategy for task auto-assignment
 */
enum class AssignmentStrategy {
    ROUND_ROBIN,
    LEAST_LOADED
}

/**
 * Tenant status
 */
enum class TenantStatus {
    ACTIVE,
    INACTIVE,
    MIGRATING,
    SUSPENDED
}