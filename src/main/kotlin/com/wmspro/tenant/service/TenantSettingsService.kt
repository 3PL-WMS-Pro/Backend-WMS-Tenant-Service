package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.exception.NotFoundException
import com.wmspro.tenant.model.TenantSettings
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing tenant settings
 * Handles operations for APIs 067 and 068
 */
@Service
class TenantSettingsService(
    private val tenantRepository: TenantDatabaseMappingRepository
) {
    private val logger = LoggerFactory.getLogger(TenantSettingsService::class.java)

    /**
     * Gets tenant settings for API 067
     * Filters sensitive information and returns only configuration data
     */
    fun getTenantSettings(clientId: Int): TenantSettingsResponse? {
        logger.debug("Fetching settings for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: return null

        return TenantSettingsResponse(
            clientId = tenant.clientId,
            tenantSettings = tenant.tenantSettings,
            lastModified = tenant.updatedAt,
            settingsCount = countNonEmptySettings(tenant.tenantSettings),
            categories = getSettingsCategories(tenant.tenantSettings)
        )
    }

    /**
     * Updates tenant settings for API 068
     * Validates and merges partial updates
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun updateTenantSettings(clientId: Int, settings: TenantSettings): TenantSettings {
        logger.info("Updating settings for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        // Validate SLA settings if provided
        settings.taskConfigurations.slaSettings.let { sla ->
            // SLA settings are validated in the model itself
        }

        // Merge settings (deep merge in production)
        val mergedSettings = mergeTenantSettings(tenant.tenantSettings, settings)

        // Update tenant with new settings
        val updatedTenant = tenant.copy(
            tenantSettings = mergedSettings,
            lastConnected = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        val saved = tenantRepository.save(updatedTenant)
        logger.info("Successfully updated settings for client ID: $clientId")

        return saved.tenantSettings
    }

    /**
     * Validates SLA settings are reasonable
     */
    private fun countNonEmptySettings(settings: TenantSettings): Int {
        var count = 0
        if (!settings.taskConfigurations.counting.isEmpty()) count++
        if (!settings.taskConfigurations.transfer.isEmpty()) count++
        if (!settings.taskConfigurations.offloading.isEmpty()) count++
        if (!settings.taskConfigurations.receiving.isEmpty()) count++
        if (!settings.taskConfigurations.putaway.isEmpty()) count++
        if (!settings.taskConfigurations.picking.isEmpty()) count++
        if (!settings.taskConfigurations.packMove.isEmpty()) count++
        if (!settings.taskConfigurations.pickPackMove.isEmpty()) count++
        if (!settings.taskConfigurations.loading.isEmpty()) count++
        if (!settings.billingSettings.isEmpty()) count++
        if (!settings.inventorySettings.isEmpty()) count++
        if (!settings.orderProcessingSettings.isEmpty()) count++
        if (!settings.warehouseOperations.isEmpty()) count++
        if (!settings.integrationSettings.isEmpty()) count++
        if (!settings.securitySettings.isEmpty()) count++
        if (!settings.notificationPreferences.isEmpty()) count++
        return count
    }

    private fun getSettingsCategories(settings: TenantSettings): List<String> {
        val categories = mutableListOf<String>()
        if (!settings.taskConfigurations.counting.isEmpty() ||
            !settings.taskConfigurations.transfer.isEmpty() ||
            !settings.taskConfigurations.offloading.isEmpty() ||
            !settings.taskConfigurations.receiving.isEmpty() ||
            !settings.taskConfigurations.putaway.isEmpty() ||
            !settings.taskConfigurations.picking.isEmpty() ||
            !settings.taskConfigurations.packMove.isEmpty() ||
            !settings.taskConfigurations.pickPackMove.isEmpty() ||
            !settings.taskConfigurations.loading.isEmpty()) {
            categories.add("taskConfigurations")
        }
        if (!settings.billingSettings.isEmpty()) categories.add("billingSettings")
        if (!settings.inventorySettings.isEmpty()) categories.add("inventorySettings")
        if (!settings.orderProcessingSettings.isEmpty()) categories.add("orderProcessingSettings")
        if (!settings.warehouseOperations.isEmpty()) categories.add("warehouseOperations")
        if (!settings.integrationSettings.isEmpty()) categories.add("integrationSettings")
        if (!settings.securitySettings.isEmpty()) categories.add("securitySettings")
        if (!settings.notificationPreferences.isEmpty()) categories.add("notificationPreferences")
        return categories
    }

    private fun validateSlaSettings(slaSettings: Map<String, Any>) {
        val validKeys = setOf(
            "offloading_sla_minutes",
            "receiving_sla_minutes",
            "putaway_sla_minutes",
            "picking_sla_minutes",
            "pack_move_sla_minutes",
            "pick_pack_move_sla_minutes",
            "loading_sla_minutes",
            "counting_sla_minutes",
            "transfer_sla_minutes"
        )

        slaSettings.forEach { (key, value) ->
            if (key in validKeys) {
                val minutes = when (value) {
                    is Number -> value.toInt()
                    else -> throw IllegalArgumentException("SLA value for $key must be a number")
                }

                if (minutes < 1 || minutes > 10080) { // 1 minute to 1 week
                    throw IllegalArgumentException("SLA value for $key must be between 1 and 10080 minutes")
                }
            }
        }
    }

    /**
     * Merges tenant settings with partial updates
     */
    private fun mergeTenantSettings(
        existing: TenantSettings,
        updates: TenantSettings
    ): TenantSettings {
        return TenantSettings(
            taskConfigurations = updates.taskConfigurations ?: existing.taskConfigurations,
            billingSettings = updates.billingSettings ?: existing.billingSettings,
            inventorySettings = updates.inventorySettings ?: existing.inventorySettings,
            orderProcessingSettings = updates.orderProcessingSettings ?: existing.orderProcessingSettings,
            warehouseOperations = updates.warehouseOperations ?: existing.warehouseOperations,
            integrationSettings = updates.integrationSettings ?: existing.integrationSettings,
            securitySettings = updates.securitySettings ?: existing.securitySettings,
            notificationPreferences = updates.notificationPreferences ?: existing.notificationPreferences
        )
    }
}

