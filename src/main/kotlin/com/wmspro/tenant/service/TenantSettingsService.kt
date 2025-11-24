package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.exception.NotFoundException
import com.wmspro.tenant.model.*
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
     * Counts non-empty settings categories
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
        if (settings.emailConfigs.isNotEmpty()) count++
        if (settings.emailTemplates.grnEmail != null ||
            settings.emailTemplates.ginEmail != null ||
            settings.emailTemplates.invoiceEmail != null ||
            settings.emailTemplates.packingListEmail != null ||
            settings.emailTemplates.deliveryNoteEmail != null) count++
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
        if (settings.emailConfigs.isNotEmpty()) categories.add("emailConfigs")
        if (settings.emailTemplates.grnEmail != null ||
            settings.emailTemplates.ginEmail != null ||
            settings.emailTemplates.invoiceEmail != null ||
            settings.emailTemplates.packingListEmail != null ||
            settings.emailTemplates.deliveryNoteEmail != null) {
            categories.add("emailTemplates")
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
            emailConfigs = if (updates.emailConfigs.isNotEmpty()) updates.emailConfigs else existing.emailConfigs,
            emailTemplates = updates.emailTemplates ?: existing.emailTemplates,
            billingSettings = updates.billingSettings ?: existing.billingSettings,
            inventorySettings = updates.inventorySettings ?: existing.inventorySettings,
            orderProcessingSettings = updates.orderProcessingSettings ?: existing.orderProcessingSettings,
            warehouseOperations = updates.warehouseOperations ?: existing.warehouseOperations,
            integrationSettings = updates.integrationSettings ?: existing.integrationSettings,
            securitySettings = updates.securitySettings ?: existing.securitySettings,
            notificationPreferences = updates.notificationPreferences ?: existing.notificationPreferences
        )
    }

    // ==================== SLA Settings Methods ====================

    /**
     * Gets SLA settings for the tenant
     */
    fun getSlaSettings(clientId: Int): SlaSettingsResponse {
        logger.debug("Fetching SLA settings for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        return SlaSettingsResponse(
            slaSettings = tenant.tenantSettings.taskConfigurations.slaSettings,
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Updates SLA settings for the tenant (partial update)
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun updateSlaSettings(clientId: Int, request: UpdateSlaSettingsRequest): SlaSettings {
        logger.info("Updating SLA settings for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val existingSla = tenant.tenantSettings.taskConfigurations.slaSettings

        // Merge with existing settings (only update non-null fields)
        val updatedSla = SlaSettings(
            countingSlaMinutes = request.countingSlaMinutes ?: existingSla.countingSlaMinutes,
            transferSlaMinutes = request.transferSlaMinutes ?: existingSla.transferSlaMinutes,
            offloadingSlaMinutes = request.offloadingSlaMinutes ?: existingSla.offloadingSlaMinutes,
            receivingSlaMinutes = request.receivingSlaMinutes ?: existingSla.receivingSlaMinutes,
            putawaySlaMinutes = request.putawaySlaMinutes ?: existingSla.putawaySlaMinutes,
            pickingSlaMinutes = request.pickingSlaMinutes ?: existingSla.pickingSlaMinutes,
            packMoveSlaMinutes = request.packMoveSlaMinutes ?: existingSla.packMoveSlaMinutes,
            pickPackMoveSlaMinutes = request.pickPackMoveSlaMinutes ?: existingSla.pickPackMoveSlaMinutes,
            loadingSlaMinutes = request.loadingSlaMinutes ?: existingSla.loadingSlaMinutes,
            escalationAfterMinutes = request.escalationAfterMinutes ?: existingSla.escalationAfterMinutes
        )

        val updatedTaskConfigs = tenant.tenantSettings.taskConfigurations.copy(slaSettings = updatedSla)
        val updatedSettings = tenant.tenantSettings.copy(taskConfigurations = updatedTaskConfigs)
        val updatedTenant = tenant.copy(tenantSettings = updatedSettings, updatedAt = LocalDateTime.now())

        tenantRepository.save(updatedTenant)
        logger.info("Successfully updated SLA settings for client ID: $clientId")

        return updatedSla
    }

    // ==================== Email Config Methods ====================

    /**
     * Gets all email configs for the tenant
     */
    fun getEmailConfigs(clientId: Int): EmailConfigListResponse {
        logger.debug("Fetching email configs for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        return EmailConfigListResponse(
            configs = tenant.tenantSettings.emailConfigs,
            count = tenant.tenantSettings.emailConfigs.size,
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Gets a specific email config by key
     */
    fun getEmailConfigByKey(clientId: Int, configKey: String): EmailConfigResponse {
        logger.debug("Fetching email config '$configKey' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val config = tenant.tenantSettings.emailConfigs[configKey]
            ?: throw NotFoundException("Email config not found with key: $configKey")

        return EmailConfigResponse(
            configKey = configKey,
            config = config,
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Adds a new email config
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun addEmailConfig(clientId: Int, request: EmailConfigRequest): EmailConfig {
        logger.info("Adding email config '${request.configKey}' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        // Check if config key already exists
        if (tenant.tenantSettings.emailConfigs.containsKey(request.configKey)) {
            throw IllegalArgumentException("Email config with key '${request.configKey}' already exists. Use update endpoint instead.")
        }

        val newConfig = request.toEmailConfig()
        val updatedConfigs = tenant.tenantSettings.emailConfigs + (request.configKey to newConfig)
        val updatedSettings = tenant.tenantSettings.copy(emailConfigs = updatedConfigs)
        val updatedTenant = tenant.copy(tenantSettings = updatedSettings, updatedAt = LocalDateTime.now())

        tenantRepository.save(updatedTenant)
        logger.info("Successfully added email config '${request.configKey}' for client ID: $clientId")

        return newConfig
    }

    /**
     * Updates an existing email config
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun updateEmailConfig(clientId: Int, configKey: String, request: EmailConfigRequest): EmailConfig {
        logger.info("Updating email config '$configKey' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        // Check if config key exists
        if (!tenant.tenantSettings.emailConfigs.containsKey(configKey)) {
            throw NotFoundException("Email config not found with key: $configKey")
        }

        val updatedConfig = request.toEmailConfig()
        val updatedConfigs = tenant.tenantSettings.emailConfigs + (configKey to updatedConfig)
        val updatedSettings = tenant.tenantSettings.copy(emailConfigs = updatedConfigs)
        val updatedTenant = tenant.copy(tenantSettings = updatedSettings, updatedAt = LocalDateTime.now())

        tenantRepository.save(updatedTenant)
        logger.info("Successfully updated email config '$configKey' for client ID: $clientId")

        return updatedConfig
    }

    /**
     * Deletes an email config
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun deleteEmailConfig(clientId: Int, configKey: String) {
        logger.info("Deleting email config '$configKey' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        // Check if config key exists
        if (!tenant.tenantSettings.emailConfigs.containsKey(configKey)) {
            throw NotFoundException("Email config not found with key: $configKey")
        }

        val updatedConfigs = tenant.tenantSettings.emailConfigs - configKey
        val updatedSettings = tenant.tenantSettings.copy(emailConfigs = updatedConfigs)
        val updatedTenant = tenant.copy(tenantSettings = updatedSettings, updatedAt = LocalDateTime.now())

        tenantRepository.save(updatedTenant)
        logger.info("Successfully deleted email config '$configKey' for client ID: $clientId")
    }

    // ==================== Email Template Methods ====================

    /**
     * Gets all email templates
     */
    fun getEmailTemplates(clientId: Int): EmailTemplatesResponse {
        logger.debug("Fetching email templates for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val templates = tenant.tenantSettings.emailTemplates
        val templateMap = mapOf(
            EmailTemplateType.GRN to templates.grnEmail,
            EmailTemplateType.GIN to templates.ginEmail,
            EmailTemplateType.INVOICE to templates.invoiceEmail,
            EmailTemplateType.PACKING_LIST to templates.packingListEmail,
            EmailTemplateType.DELIVERY_NOTE to templates.deliveryNoteEmail
        )

        val configuredCount = templateMap.values.count { it != null }

        return EmailTemplatesResponse(
            templates = templateMap,
            configuredCount = configuredCount,
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Gets a specific email template by type
     */
    fun getEmailTemplateByType(clientId: Int, templateType: EmailTemplateType): EmailTemplateResponse {
        logger.debug("Fetching email template '$templateType' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val template = getTemplateByType(tenant.tenantSettings.emailTemplates, templateType)

        return EmailTemplateResponse(
            templateType = templateType,
            template = template,
            isConfigured = template != null,
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Sets (creates or updates) an email template for a specific type
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun setEmailTemplate(clientId: Int, templateType: EmailTemplateType, request: SetEmailTemplateRequest): EmailTemplate {
        logger.info("Setting email template '$templateType' for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        // Validate that the referenced email config exists (if not "default")
        if (request.emailConfigKey != "default" && !tenant.tenantSettings.emailConfigs.containsKey(request.emailConfigKey)) {
            throw IllegalArgumentException("Email config '${request.emailConfigKey}' does not exist. Create it first or use 'default'.")
        }

        val newTemplate = request.toEmailTemplate()
        val existingTemplates = tenant.tenantSettings.emailTemplates

        val updatedTemplates = when (templateType) {
            EmailTemplateType.GRN -> existingTemplates.copy(grnEmail = newTemplate)
            EmailTemplateType.GIN -> existingTemplates.copy(ginEmail = newTemplate)
            EmailTemplateType.INVOICE -> existingTemplates.copy(invoiceEmail = newTemplate)
            EmailTemplateType.PACKING_LIST -> existingTemplates.copy(packingListEmail = newTemplate)
            EmailTemplateType.DELIVERY_NOTE -> existingTemplates.copy(deliveryNoteEmail = newTemplate)
        }

        val updatedSettings = tenant.tenantSettings.copy(emailTemplates = updatedTemplates)
        val updatedTenant = tenant.copy(tenantSettings = updatedSettings, updatedAt = LocalDateTime.now())

        tenantRepository.save(updatedTenant)
        logger.info("Successfully set email template '$templateType' for client ID: $clientId")

        return newTemplate
    }

    /**
     * Helper to get template by type
     */
    private fun getTemplateByType(templates: EmailTemplates, type: EmailTemplateType): EmailTemplate? {
        return when (type) {
            EmailTemplateType.GRN -> templates.grnEmail
            EmailTemplateType.GIN -> templates.ginEmail
            EmailTemplateType.INVOICE -> templates.invoiceEmail
            EmailTemplateType.PACKING_LIST -> templates.packingListEmail
            EmailTemplateType.DELIVERY_NOTE -> templates.deliveryNoteEmail
        }
    }

    // ==================== S3 Configuration Methods ====================

    /**
     * Gets S3 configuration for the tenant (masks sensitive credentials)
     */
    fun getS3Configuration(clientId: Int): S3ConfigurationResponse {
        logger.debug("Fetching S3 configuration for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val s3Config = tenant.s3Configuration

        return S3ConfigurationResponse(
            bucketName = s3Config.bucketName,
            region = s3Config.region,
            accessKeyMasked = maskCredential(s3Config.accessKey),
            secretKeyMasked = maskCredential(s3Config.secretKey),
            bucketPrefix = s3Config.bucketPrefix,
            isConfigured = s3Config.bucketName.isNotBlank() && s3Config.accessKey.isNotBlank(),
            lastModified = tenant.updatedAt
        )
    }

    /**
     * Sets (overwrites) S3 configuration for the tenant
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun setS3Configuration(clientId: Int, request: SetS3ConfigurationRequest): S3ConfigurationResponse {
        logger.info("Setting S3 configuration for client ID: $clientId")

        val tenant = tenantRepository.findByClientId(clientId).orElse(null)
            ?: throw NotFoundException("Tenant not found with client ID: $clientId")

        val newS3Config = S3Configuration(
            bucketName = request.bucketName,
            region = request.region,
            accessKey = request.accessKey,
            secretKey = request.secretKey,
            bucketPrefix = request.bucketPrefix
        )

        val updatedTenant = tenant.copy(
            s3Configuration = newS3Config,
            updatedAt = LocalDateTime.now()
        )

        tenantRepository.save(updatedTenant)
        logger.info("Successfully updated S3 configuration for client ID: $clientId")

        return S3ConfigurationResponse(
            bucketName = newS3Config.bucketName,
            region = newS3Config.region,
            accessKeyMasked = maskCredential(newS3Config.accessKey),
            secretKeyMasked = maskCredential(newS3Config.secretKey),
            bucketPrefix = newS3Config.bucketPrefix,
            isConfigured = true,
            lastModified = LocalDateTime.now()
        )
    }

    /**
     * Masks a credential showing only the last 4 characters
     */
    private fun maskCredential(credential: String): String {
        return if (credential.length > 4) {
            "****" + credential.takeLast(4)
        } else {
            "****"
        }
    }
}

