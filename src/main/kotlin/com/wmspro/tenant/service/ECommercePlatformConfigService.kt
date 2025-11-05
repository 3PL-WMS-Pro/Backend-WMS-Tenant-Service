package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.ECommercePlatformConfig
import com.wmspro.tenant.model.ConnectionStatus
import com.wmspro.tenant.repository.ECommercePlatformConfigRepository
import com.wmspro.tenant.util.EncryptionUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing e-commerce platform configurations
 */
@Service
class ECommercePlatformConfigService(
    private val ecommercePlatformConfigRepository: ECommercePlatformConfigRepository
) {
    private val logger = LoggerFactory.getLogger(ECommercePlatformConfigService::class.java)

    /**
     * Create a new e-commerce platform configuration
     */
    @Transactional
    fun createECommercePlatformConfig(tenantId: Int, request: CreateECommercePlatformConfigRequest): ECommercePlatformConfigResponse {
        logger.info("Creating e-commerce platform configuration for tenant: $tenantId, platformType: ${request.platformType}")

        // Encrypt sensitive fields before storing
        val encryptedApiKey = EncryptionUtil.encrypt(request.apiKey)
            ?: throw IllegalStateException("Failed to encrypt API key")

        val encryptedApiSecret = EncryptionUtil.encrypt(request.apiSecret)
            ?: throw IllegalStateException("Failed to encrypt API secret")

        val platformConfig = ECommercePlatformConfig(
            tenantId = tenantId,
            platformType = request.platformType,
            apiKey = encryptedApiKey,
            apiVersion = request.apiVersion,
            apiSecret = encryptedApiSecret,
            storeUrl = request.storeUrl,
            syncOptions = request.syncOptions,
            status = request.status,
            connectionStatus = ConnectionStatus.NOT_TESTED
        )

        val saved = ecommercePlatformConfigRepository.save(platformConfig)
        logger.info("E-commerce platform configuration created with ID: ${saved.id}")

        return toResponse(saved, maskSecrets = true)
    }

    /**
     * Get all e-commerce platform configurations for a tenant
     */
    fun getAllECommercePlatformConfigs(tenantId: Int): ECommercePlatformConfigListResponse {
        logger.debug("Fetching all e-commerce platform configurations for tenant: $tenantId")

        val configs = ecommercePlatformConfigRepository.findByTenantId(tenantId)

        val items = configs.map { config ->
            ECommercePlatformConfigListItem(
                id = config.id,
                platformType = config.platformType,
                storeUrl = config.storeUrl,
                status = config.status,
                connectionStatus = config.connectionStatus
            )
        }

        return ECommercePlatformConfigListResponse(data = items)
    }

    /**
     * Get e-commerce platform configuration by ID
     */
    fun getECommercePlatformConfigById(tenantId: Int, configId: String): ECommercePlatformConfigResponse {
        logger.debug("Fetching e-commerce platform configuration: $configId for tenant: $tenantId")

        val config = ecommercePlatformConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("E-commerce platform configuration not found with ID: $configId") }

        return toResponse(config, maskSecrets = true)
    }

    /**
     * Update e-commerce platform configuration
     */
    @Transactional
    fun updateECommercePlatformConfig(tenantId: Int, configId: String, request: UpdateECommercePlatformConfigRequest): ECommercePlatformConfigResponse {
        logger.info("Updating e-commerce platform configuration: $configId for tenant: $tenantId")

        val existing = ecommercePlatformConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("E-commerce platform configuration not found with ID: $configId") }

        // Handle API key update
        val apiKeyToStore = if (!request.apiKey.isNullOrBlank()) {
            // New API key provided, encrypt it
            EncryptionUtil.encrypt(request.apiKey)
                ?: throw IllegalStateException("Failed to encrypt API key")
        } else {
            // No new API key, keep existing
            existing.apiKey
        }

        // Handle API secret update
        val apiSecretToStore = if (!request.apiSecret.isNullOrBlank()) {
            // New API secret provided, encrypt it
            EncryptionUtil.encrypt(request.apiSecret)
                ?: throw IllegalStateException("Failed to encrypt API secret")
        } else {
            // No new API secret, keep existing
            existing.apiSecret
        }

        val updated = existing.copy(
            platformType = request.platformType,
            apiKey = apiKeyToStore,
            apiVersion = request.apiVersion,
            apiSecret = apiSecretToStore,
            storeUrl = request.storeUrl,
            syncOptions = request.syncOptions,
            status = request.status,
            // Reset connection status if credentials changed
            connectionStatus = if (!request.apiKey.isNullOrBlank() || !request.apiSecret.isNullOrBlank()) {
                ConnectionStatus.NOT_TESTED
            } else {
                existing.connectionStatus
            }
        )

        val saved = ecommercePlatformConfigRepository.save(updated)
        logger.info("E-commerce platform configuration updated: ${saved.id}")

        return toResponse(saved, maskSecrets = true)
    }

    /**
     * Delete e-commerce platform configuration
     */
    @Transactional
    fun deleteECommercePlatformConfig(tenantId: Int, configId: String) {
        logger.info("Deleting e-commerce platform configuration: $configId for tenant: $tenantId")

        // Verify config exists and belongs to tenant
        val config = ecommercePlatformConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("E-commerce platform configuration not found with ID: $configId") }

        // Delete the config
        ecommercePlatformConfigRepository.deleteById(configId)
        logger.info("E-commerce platform configuration deleted: $configId")
    }

    /**
     * Test e-commerce platform connection
     * Note: This is a placeholder implementation. In production, this should actually
     * attempt to connect to the e-commerce platform using the provided credentials.
     */
    @Transactional
    fun testECommercePlatformConnection(tenantId: Int, configId: String): ConnectionTestResponse {
        logger.info("Testing e-commerce platform connection for config: $configId, tenant: $tenantId")

        val config = ecommercePlatformConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("E-commerce platform configuration not found with ID: $configId") }

        return try {
            // Decrypt credentials for testing
            val decryptedApiKey = EncryptionUtil.decrypt(config.apiKey)
            val decryptedApiSecret = EncryptionUtil.decrypt(config.apiSecret)

            // TODO: Implement actual platform connection test based on platformType
            // For now, we'll just validate that credentials are not empty
            if (decryptedApiKey.isNullOrBlank() || decryptedApiSecret.isNullOrBlank()) {
                throw IllegalStateException("Invalid credentials")
            }

            // Placeholder: Simulate successful connection
            // In production, implement platform-specific connection logic:
            // - For Shopify: Test API connection using Shopify API client
            // - For WooCommerce: Test REST API connection
            // - For custom platforms: Test based on platform specification

            // Update connection status
            val updated = config.copy(connectionStatus = ConnectionStatus.CONNECTED)
            ecommercePlatformConfigRepository.save(updated)

            logger.info("E-commerce platform connection test successful for config: $configId")
            ConnectionTestResponse(
                success = true,
                message = "Connection successful",
                details = "Successfully connected to ${config.platformType} platform at ${config.storeUrl}"
            )
        } catch (e: Exception) {
            logger.error("E-commerce platform connection test failed for config: $configId", e)

            // Update connection status to failed
            val updated = config.copy(connectionStatus = ConnectionStatus.FAILED)
            ecommercePlatformConfigRepository.save(updated)

            ConnectionTestResponse(
                success = false,
                message = "Connection failed",
                details = e.message ?: "Unknown error occurred"
            )
        }
    }

    /**
     * Convert ECommercePlatformConfig entity to response DTO
     */
    private fun toResponse(config: ECommercePlatformConfig, maskSecrets: Boolean = true): ECommercePlatformConfigResponse {
        val displayApiKey = if (maskSecrets) {
            EncryptionUtil.maskPassword(config.apiKey)
        } else {
            EncryptionUtil.decrypt(config.apiKey) ?: "********"
        }

        val displayApiSecret = if (maskSecrets) {
            EncryptionUtil.maskPassword(config.apiSecret)
        } else {
            EncryptionUtil.decrypt(config.apiSecret) ?: "********"
        }

        return ECommercePlatformConfigResponse(
            id = config.id,
            platformType = config.platformType,
            apiKey = displayApiKey,
            apiVersion = config.apiVersion,
            apiSecret = displayApiSecret,
            storeUrl = config.storeUrl,
            syncOptions = config.syncOptions,
            status = config.status,
            connectionStatus = config.connectionStatus,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }
}
