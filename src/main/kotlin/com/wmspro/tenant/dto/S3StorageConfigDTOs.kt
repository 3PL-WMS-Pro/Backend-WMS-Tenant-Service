package com.wmspro.tenant.dto

import com.wmspro.tenant.model.S3StorageConfigStatus
import com.wmspro.tenant.model.ConnectionStatus
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * Request DTO for creating S3 storage configuration
 */
data class CreateS3StorageConfigRequest(
    @field:NotBlank(message = "Bucket name cannot be blank")
    val bucketName: String,

    @field:NotBlank(message = "Region cannot be blank")
    val region: String,

    @field:NotBlank(message = "Access Key ID cannot be blank")
    val accessKeyId: String,

    @field:NotBlank(message = "API Secret Key cannot be blank")
    val apiSecretKey: String,

    @field:NotBlank(message = "Prefix cannot be blank")
    val prefix: String,

    val status: S3StorageConfigStatus = S3StorageConfigStatus.ACTIVE
)

/**
 * Request DTO for updating S3 storage configuration
 */
data class UpdateS3StorageConfigRequest(
    @field:NotBlank(message = "Bucket name cannot be blank")
    val bucketName: String,

    @field:NotBlank(message = "Region cannot be blank")
    val region: String,

    val accessKeyId: String? = null, // Optional on update - if not provided, keep existing

    val apiSecretKey: String? = null, // Optional on update - if not provided, keep existing

    @field:NotBlank(message = "Prefix cannot be blank")
    val prefix: String,

    val status: S3StorageConfigStatus = S3StorageConfigStatus.ACTIVE
)

/**
 * Response DTO for S3 storage configuration
 */
data class S3StorageConfigResponse(
    val id: String,
    val bucketName: String,
    val region: String,
    val accessKeyId: String, // Masked for security
    val apiSecretKey: String, // Masked for security
    val prefix: String,
    val status: S3StorageConfigStatus,
    val connectionStatus: ConnectionStatus,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * List item DTO for S3 storage configuration
 */
data class S3StorageConfigListItem(
    val id: String,
    val bucketName: String,
    val region: String,
    val prefix: String,
    val status: S3StorageConfigStatus,
    val connectionStatus: ConnectionStatus
)

/**
 * List response wrapper
 */
data class S3StorageConfigListResponse(
    val data: List<S3StorageConfigListItem>
)

/**
 * Request DTO for testing S3 connection
 */
data class TestS3ConnectionRequest(
    val bucketName: String,
    val region: String,
    val accessKeyId: String,
    val apiSecretKey: String,
    val prefix: String
)

/**
 * Response DTO for connection test
 */
data class ConnectionTestResponse(
    val success: Boolean,
    val message: String,
    val details: String? = null
)
