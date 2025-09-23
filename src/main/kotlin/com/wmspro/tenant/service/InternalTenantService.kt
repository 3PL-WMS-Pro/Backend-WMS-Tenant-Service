package com.wmspro.tenant.service

import com.wmspro.tenant.controller.InactiveTenantException
import com.wmspro.tenant.controller.MongoConnectionResponse
import com.wmspro.tenant.model.S3Configuration
import com.wmspro.tenant.model.TenantSettings
import com.wmspro.tenant.model.TenantStatus
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for internal service-to-service tenant operations
 * Handles secure access to tenant configurations for other microservices
 */
@Service
class InternalTenantService(
    private val tenantRepository: TenantDatabaseMappingRepository
) {
    private val logger = LoggerFactory.getLogger(InternalTenantService::class.java)

    // Cache for frequently accessed settings with TTL
    private val settingsCache = ConcurrentHashMap<String, CachedSettings>()
    private val CACHE_TTL_MINUTES = 5L

    /**
     * Gets database connection for internal services (API 069)
     */
    fun getDatabaseConnection(clientId: Int): MongoConnectionResponse? {
        logger.debug("Retrieving database connection for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return null

        // Check tenant is active
        if (tenant.status != TenantStatus.ACTIVE) {
            throw InactiveTenantException("Tenant $clientId is inactive")
        }

        // Update last connected timestamp
        val updatedTenant = tenant.copy(lastConnected = LocalDateTime.now())
        tenantRepository.save(updatedTenant)

        // Return decrypted connection (in production, decrypt here)
        return MongoConnectionResponse(
            url = tenant.mongoConnection.url,
            databaseName = tenant.mongoConnection.databaseName,
            connectionOptions = tenant.mongoConnection.connectionOptions ?: mapOf(
                "maxPoolSize" to 10,
                "minPoolSize" to 2,
                "retryWrites" to true,
                "w" to "majority"
            )
        )
    }

    /**
     * Gets S3 configuration for file service (API 071)
     */
    fun getS3Configuration(clientId: Int): S3Configuration? {
        logger.debug("Retrieving S3 configuration for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return null

        // Check tenant is active
        if (tenant.status != TenantStatus.ACTIVE) {
            throw InactiveTenantException("Tenant $clientId is inactive")
        }

        // Return decrypted S3 config (in production, decrypt credentials here)
        return tenant.s3Configuration
    }

    /**
     * Gets all tenant settings for internal services (API 072)
     */
    @Cacheable(value = ["internalSettings"], key = "#clientId")
    fun getAllTenantSettings(clientId: Int): TenantSettings? {
        logger.debug("Retrieving all tenant settings for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return null

        return tenant.tenantSettings
    }

    /**
     * Gets specific tenant settings by path (API 072)
     * Supports dot notation like 'task_configurations.sla_settings.picking_sla_minutes'
     */
    fun getTenantSettingsByPath(clientId: Int, path: String): Any? {
        val cacheKey = "$clientId:$path"

        // Check cache first
        settingsCache[cacheKey]?.let { cached ->
            if (cached.isValid()) {
                logger.debug("Returning cached settings for $cacheKey")
                return cached.value
            } else {
                settingsCache.remove(cacheKey)
            }
        }

        val settings = getAllTenantSettings(clientId) ?: return null

        val result = navigateSettingsPath(settings, path)

        // Cache the result
        if (result != null) {
            settingsCache[cacheKey] = CachedSettings(result, LocalDateTime.now())
        }

        return result
    }

    /**
     * Navigates through settings object using dot notation path
     */
    private fun navigateSettingsPath(settings: TenantSettings, path: String): Any? {
        val pathParts = path.split(".")
        var current: Any? = settings

        for (part in pathParts) {
            current = when (current) {
                is TenantSettings -> getSettingsField(current, part)
                is Map<*, *> -> current[part]
                else -> null
            }

            if (current == null) {
                logger.warn("Settings path not found: $path at part: $part")
                return null
            }
        }

        return current
    }

    /**
     * Gets field from TenantSettings object by name
     */
    private fun getSettingsField(settings: TenantSettings, fieldName: String): Any? {
        return when (fieldName) {
            "taskConfigurations", "task_configurations" -> settings.taskConfigurations
            "billingSettings", "billing_settings" -> settings.billingSettings
            "inventorySettings", "inventory_settings" -> settings.inventorySettings
            "orderProcessingSettings", "order_processing_settings" -> settings.orderProcessingSettings
            "warehouseOperations", "warehouse_operations" -> settings.warehouseOperations
            "integrationSettings", "integration_settings" -> settings.integrationSettings
            "securitySettings", "security_settings" -> settings.securitySettings
            "notificationPreferences", "notification_preferences" -> settings.notificationPreferences
            else -> {
                // Handle nested fields in task configurations
                if (fieldName == "slaSettings" || fieldName == "sla_settings") {
                    settings.taskConfigurations?.get("slaSettings")
                } else if (fieldName == "autoAssignment" || fieldName == "auto_assignment") {
                    settings.taskConfigurations?.get("autoAssignment")
                } else {
                    null
                }
            }
        }
    }

    /**
     * Cached settings with timestamp
     */
    private data class CachedSettings(
        val value: Any,
        val timestamp: LocalDateTime
    ) {
        fun isValid(): Boolean {
            return timestamp.plusMinutes(5).isAfter(LocalDateTime.now())
        }
    }
}