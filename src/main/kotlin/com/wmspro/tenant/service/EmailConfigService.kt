package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.EmailConfig
import com.wmspro.tenant.repository.EmailConfigRepository
import com.wmspro.tenant.repository.EmailTemplateRepository
import com.wmspro.tenant.util.EncryptionUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing email configurations
 */
@Service
class EmailConfigService(
    private val emailConfigRepository: EmailConfigRepository,
    private val emailTemplateRepository: EmailTemplateRepository
) {
    private val logger = LoggerFactory.getLogger(EmailConfigService::class.java)

    /**
     * Create a new email configuration
     */
    @Transactional
    fun createEmailConfig(tenantId: Int, request: CreateEmailConfigRequest): EmailConfigResponse {
        logger.info("Creating email configuration for tenant: $tenantId, connectionName: ${request.connectionName}")

        // Check if connection name already exists for this tenant
        if (emailConfigRepository.existsByConnectionNameAndTenantId(request.connectionName, tenantId)) {
            throw IllegalArgumentException("Email configuration with connection name '${request.connectionName}' already exists")
        }

        // Encrypt password before storing
        val encryptedPassword = EncryptionUtil.encrypt(request.password)
            ?: throw IllegalStateException("Failed to encrypt password")

        val emailConfig = EmailConfig(
            tenantId = tenantId,
            connectionName = request.connectionName,
            smtpHost = request.smtpHost,
            smtpPort = request.smtpPort,
            username = request.username,
            password = encryptedPassword,
            fromEmail = request.fromEmail,
            fromName = request.fromName,
            useTLS = request.useTLS,
            useSSL = request.useSSL,
            authEnabled = request.authEnabled,
            status = request.status
        )

        val saved = emailConfigRepository.save(emailConfig)
        logger.info("Email configuration created with ID: ${saved.id}")

        return toResponse(saved, maskPassword = true)
    }

    /**
     * Get all email configurations for a tenant
     */
    fun getAllEmailConfigs(tenantId: Int): EmailConfigListResponse {
        logger.debug("Fetching all email configurations for tenant: $tenantId")

        val configs = emailConfigRepository.findByTenantId(tenantId)

        val items = configs.map { config ->
            EmailConfigListItem(
                id = config.id,
                connectionName = config.connectionName,
                smtpHost = config.smtpHost,
                smtpPort = config.smtpPort,
                fromEmail = config.fromEmail,
                status = config.status
            )
        }

        return EmailConfigListResponse(data = items)
    }

    /**
     * Get email configuration by ID
     */
    fun getEmailConfigById(tenantId: Int, configId: String): EmailConfigResponse {
        logger.debug("Fetching email configuration: $configId for tenant: $tenantId")

        val config = emailConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("Email configuration not found with ID: $configId") }

        return toResponse(config, maskPassword = true)
    }

    /**
     * Update email configuration
     */
    @Transactional
    fun updateEmailConfig(tenantId: Int, configId: String, request: UpdateEmailConfigRequest): EmailConfigResponse {
        logger.info("Updating email configuration: $configId for tenant: $tenantId")

        val existing = emailConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("Email configuration not found with ID: $configId") }

        // Check if connection name is being changed and if it conflicts
        if (request.connectionName != existing.connectionName) {
            if (emailConfigRepository.existsByConnectionNameAndTenantId(request.connectionName, tenantId)) {
                throw IllegalArgumentException("Email configuration with connection name '${request.connectionName}' already exists")
            }
        }

        // Handle password update
        val passwordToStore = if (request.password != null && request.password.isNotBlank()) {
            // New password provided, encrypt it
            EncryptionUtil.encrypt(request.password)
                ?: throw IllegalStateException("Failed to encrypt password")
        } else {
            // No new password, keep existing
            existing.password
        }

        val updated = existing.copy(
            connectionName = request.connectionName,
            smtpHost = request.smtpHost,
            smtpPort = request.smtpPort,
            username = request.username,
            password = passwordToStore,
            fromEmail = request.fromEmail,
            fromName = request.fromName,
            useTLS = request.useTLS,
            useSSL = request.useSSL,
            authEnabled = request.authEnabled,
            status = request.status
        )

        val saved = emailConfigRepository.save(updated)
        logger.info("Email configuration updated: ${saved.id}")

        return toResponse(saved, maskPassword = true)
    }

    /**
     * Delete email configuration
     * Removes reference from email templates that use this config
     */
    @Transactional
    fun deleteEmailConfig(tenantId: Int, configId: String) {
        logger.info("Deleting email configuration: $configId for tenant: $tenantId")

        // Verify config exists and belongs to tenant
        val config = emailConfigRepository.findByIdAndTenantId(configId, tenantId)
            .orElseThrow { IllegalArgumentException("Email configuration not found with ID: $configId") }

        // Find all templates using this config and remove the reference
        val templatesUsingConfig = emailTemplateRepository.findByEmailConfigId(configId)
        if (templatesUsingConfig.isNotEmpty()) {
            logger.info("Removing email config reference from ${templatesUsingConfig.size} templates")
            templatesUsingConfig.forEach { template ->
                val updated = template.copy(emailConfigId = null)
                emailTemplateRepository.save(updated)
            }
        }

        // Delete the config
        emailConfigRepository.deleteById(configId)
        logger.info("Email configuration deleted: $configId")
    }

    /**
     * Convert EmailConfig entity to response DTO
     */
    private fun toResponse(config: EmailConfig, maskPassword: Boolean = true): EmailConfigResponse {
        val displayPassword = if (maskPassword) {
            EncryptionUtil.maskPassword(config.password)
        } else {
            // Decrypt for display (if needed in future)
            EncryptionUtil.decrypt(config.password) ?: "********"
        }

        return EmailConfigResponse(
            id = config.id,
            connectionName = config.connectionName,
            smtpHost = config.smtpHost,
            smtpPort = config.smtpPort,
            username = config.username,
            password = displayPassword,
            fromEmail = config.fromEmail,
            fromName = config.fromName,
            useTLS = config.useTLS,
            useSSL = config.useSSL,
            authEnabled = config.authEnabled,
            status = config.status,
            createdAt = config.createdAt,
            updatedAt = config.updatedAt
        )
    }
}
