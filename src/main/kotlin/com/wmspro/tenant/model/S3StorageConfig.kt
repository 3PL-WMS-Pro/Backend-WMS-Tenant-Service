package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime
import java.util.UUID
import jakarta.validation.constraints.*

/**
 * S3StorageConfig Model - Stores S3 storage configuration for each tenant
 * Collection: s3_storage_configs
 * Database: Central shared database (NOT tenant-specific)
 */
@Document(collection = "s3_storage_configs")
data class S3StorageConfig(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Indexed
    val tenantId: Int,

    @field:NotBlank(message = "Bucket name cannot be blank")
    val bucketName: String,

    @field:NotBlank(message = "Region cannot be blank")
    val region: String,

    @field:NotBlank(message = "Access Key ID cannot be blank")
    val accessKeyId: String, // Stored encrypted

    @field:NotBlank(message = "API Secret Key cannot be blank")
    val apiSecretKey: String, // Stored encrypted

    @field:NotBlank(message = "Prefix cannot be blank")
    val prefix: String,

    val status: S3StorageConfigStatus = S3StorageConfigStatus.ACTIVE,

    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_TESTED,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(tenantId > 0) { "Tenant ID must be positive" }
        require(bucketName.isNotBlank()) { "Bucket name cannot be blank" }
        require(region.isNotBlank()) { "Region cannot be blank" }
        require(accessKeyId.isNotBlank()) { "Access Key ID cannot be blank" }
        require(apiSecretKey.isNotBlank()) { "API Secret Key cannot be blank" }
        require(prefix.isNotBlank()) { "Prefix cannot be blank" }
    }
}

/**
 * S3 Storage Configuration Status
 */
enum class S3StorageConfigStatus {
    ACTIVE,
    INACTIVE
}

/**
 * Connection Status
 */
enum class ConnectionStatus {
    NOT_TESTED,
    CONNECTED,
    FAILED
}
