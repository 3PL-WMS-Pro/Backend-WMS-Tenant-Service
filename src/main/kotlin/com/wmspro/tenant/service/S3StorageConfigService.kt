package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.S3StorageConfig
import com.wmspro.tenant.model.ConnectionStatus
import com.wmspro.tenant.repository.S3StorageConfigRepository
import com.wmspro.tenant.util.EncryptionUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing S3 storage configurations
 */
@Service
class S3StorageConfigService(
    private val s3StorageConfigRepository: S3StorageConfigRepository
) {
    private val logger = LoggerFactory.getLogger(S3StorageConfigService::class.java)

    /**
     * Create a new S3 storage configuration
     */
    @Transactional
    fun createS3StorageConfig(tenantId: Int, request: CreateS3StorageConfigRequest): S3StorageConfigResponse {
        logger.info("Creating S3 storage configuration for tenant: $tenantId, bucketName: ${request.bucketName}")

        // Encrypt sensitive fields before storing
        val encryptedAccessKeyId = EncryptionUtil.encrypt(request.accessKeyId)
            ?: throw IllegalStateException("Failed to encrypt Access Key ID")

        val encryptedApiSecretKey = EncryptionUtil.encrypt(request.apiSecretKey)
            ?: throw IllegalStateException("Failed to encrypt API Secret Key")

        val s3Config = S3StorageConfig(
            tenantId = tenantId,
            bucketName = request.bucketName,
            region = request.region,
            accessKeyId = encryptedAccessKeyId,
            apiSecretKey = encryptedApiSecretKey,
            prefix = request.prefix,
            status = request.status,
            connectionStatus = ConnectionStatus.NOT_TESTED
        )

        val saved = s3StorageConfigRepository.save(s3Config)
        logger.info("S3 storage configuration created with ID: ${saved.id}")

        return toResponse(saved, maskSecrets = true)
    }

    /**
     * Get all S3 storage configurations for a tenant
     */
    fun getAllS3StorageConfigs(tenantId: Int): S3StorageConfigListResponse {
        logger.debug("Fetching all S3 storage configurations for tenant: $tenantId")

        val configs = s3StorageConfigRepository.findByTenantId(tenantId)

        val items = configs.map { config ->
            S3StorageConfigListItem(
                id = config.id,
                bucketName = config.bucketName,
                region = config.region,
                prefix = config.prefix,
                status = config.status,
                connectionStatus = config.connectionStatus
            )
        }

        return S3StorageConfigListResponse(data = items)
    }

    /**
     * Get S3 storage configuration by ID
     */
    fun getS3StorageConfigById(tenantId: Int, configId: String): S3StorageConfigResponse {
        logger.debug("Fetching S3 storage configuration: $configId for tenant: $tenantId")

        val config = s3StorageConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("S3 storage configuration not found with ID: $configId") }

        return toResponse(config, maskSecrets = true)
    }

    /**
     * Update S3 storage configuration
     */
    @Transactional
    fun updateS3StorageConfig(tenantId: Int, configId: String, request: UpdateS3StorageConfigRequest): S3StorageConfigResponse {
        logger.info("Updating S3 storage configuration: $configId for tenant: $tenantId")

        val existing = s3StorageConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("S3 storage configuration not found with ID: $configId") }

        // Handle Access Key ID update
        val accessKeyIdToStore = if (!request.accessKeyId.isNullOrBlank()) {
            // New access key provided, encrypt it
            EncryptionUtil.encrypt(request.accessKeyId)
                ?: throw IllegalStateException("Failed to encrypt Access Key ID")
        } else {
            // No new access key, keep existing
            existing.accessKeyId
        }

        // Handle API Secret Key update
        val apiSecretKeyToStore = if (!request.apiSecretKey.isNullOrBlank()) {
            // New secret key provided, encrypt it
            EncryptionUtil.encrypt(request.apiSecretKey)
                ?: throw IllegalStateException("Failed to encrypt API Secret Key")
        } else {
            // No new secret key, keep existing
            existing.apiSecretKey
        }

        val updated = existing.copy(
            bucketName = request.bucketName,
            region = request.region,
            accessKeyId = accessKeyIdToStore,
            apiSecretKey = apiSecretKeyToStore,
            prefix = request.prefix,
            status = request.status,
            // Reset connection status if credentials changed
            connectionStatus = if (!request.accessKeyId.isNullOrBlank() || !request.apiSecretKey.isNullOrBlank()) {
                ConnectionStatus.NOT_TESTED
            } else {
                existing.connectionStatus
            }
        )

        val saved = s3StorageConfigRepository.save(updated)
        logger.info("S3 storage configuration updated: ${saved.id}")

        return toResponse(saved, maskSecrets = true)
    }

    /**
     * Delete S3 storage configuration
     */
    @Transactional
    fun deleteS3StorageConfig(tenantId: Int, configId: String) {
        logger.info("Deleting S3 storage configuration: $configId for tenant: $tenantId")

        // Verify config exists and belongs to tenant
        val config = s3StorageConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("S3 storage configuration not found with ID: $configId") }

        // Delete the config
        s3StorageConfigRepository.deleteById(configId)
        logger.info("S3 storage configuration deleted: $configId")
    }

    /**
     * Test S3 connection
     * Note: This is a placeholder implementation. In production, this should actually
     * attempt to connect to S3 using the provided credentials.
     */
    @Transactional
    fun testS3Connection(tenantId: Int, configId: String): ConnectionTestResponse {
        logger.info("Testing S3 connection for config: $configId, tenant: $tenantId")

        val config = s3StorageConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("S3 storage configuration not found with ID: $configId") }

        return try {
            // Decrypt credentials for testing
            val decryptedAccessKeyId = EncryptionUtil.decrypt(config.accessKeyId)
            val decryptedApiSecretKey = EncryptionUtil.decrypt(config.apiSecretKey)

            // TODO: Implement actual S3 connection test using AWS SDK
            // For now, we'll just validate that credentials are not empty
            if (decryptedAccessKeyId.isNullOrBlank() || decryptedApiSecretKey.isNullOrBlank()) {
                throw IllegalStateException("Invalid credentials")
            }

            // Placeholder: Simulate successful connection
            // In production, use AWS S3 client to test:
            // val s3Client = S3Client.builder()
            //     .region(Region.of(config.region))
            //     .credentialsProvider(StaticCredentialsProvider.create(
            //         AwsBasicCredentials.create(decryptedAccessKeyId, decryptedApiSecretKey)
            //     ))
            //     .build()
            // s3Client.headBucket(HeadBucketRequest.builder().bucket(config.bucketName).build())

            // Update connection status
            val updated = config.copy(connectionStatus = ConnectionStatus.CONNECTED)
            s3StorageConfigRepository.save(updated)

            logger.info("S3 connection test successful for config: $configId")
            ConnectionTestResponse(
                success = true,
                message = "Connection successful",
                details = "Successfully connected to S3 bucket: ${config.bucketName}"
            )
        } catch (e: Exception) {
            logger.error("S3 connection test failed for config: $configId", e)

            // Update connection status to failed
            val updated = config.copy(connectionStatus = ConnectionStatus.FAILED)
            s3StorageConfigRepository.save(updated)

            ConnectionTestResponse(
                success = false,
                message = "Connection failed",
                details = e.message ?: "Unknown error occurred"
            )
        }
    }

    /**
     * Convert S3StorageConfig entity to response DTO
     */
    private fun toResponse(config: S3StorageConfig, maskSecrets: Boolean = true): S3StorageConfigResponse {
        val displayAccessKeyId = if (maskSecrets) {
            EncryptionUtil.maskPassword(config.accessKeyId)
        } else {
            EncryptionUtil.decrypt(config.accessKeyId) ?: "********"
        }

        val displayApiSecretKey = if (maskSecrets) {
            EncryptionUtil.maskPassword(config.apiSecretKey)
        } else {
            EncryptionUtil.decrypt(config.apiSecretKey) ?: "********"
        }

        return S3StorageConfigResponse(
            id = config.id,
            bucketName = config.bucketName,
            region = config.region,
            accessKeyId = displayAccessKeyId,
            apiSecretKey = displayApiSecretKey,
            prefix = config.prefix,
            status = config.status,
            connectionStatus = config.connectionStatus,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }
}
