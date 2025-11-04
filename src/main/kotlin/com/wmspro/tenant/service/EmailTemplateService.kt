package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.EmailTemplate
import com.wmspro.tenant.repository.EmailConfigRepository
import com.wmspro.tenant.repository.EmailTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing email templates
 */
@Service
class EmailTemplateService(
    private val emailTemplateRepository: EmailTemplateRepository,
    private val emailConfigRepository: EmailConfigRepository
) {
    private val logger = LoggerFactory.getLogger(EmailTemplateService::class.java)

    /**
     * Create a new email template
     */
    @Transactional
    fun createEmailTemplate(tenantId: Int, request: CreateEmailTemplateRequest): EmailTemplateResponse {
        logger.info("Creating email template for tenant: $tenantId, templateName: ${request.templateName}")

        // Check if template name already exists for this tenant
        if (emailTemplateRepository.existsByTemplateNameAndTenantId(request.templateName, tenantId)) {
            throw IllegalArgumentException("Email template with name '${request.templateName}' already exists")
        }

        val emailTemplate = EmailTemplate(
            tenantId = tenantId,
            templateName = request.templateName,
            templateType = request.templateType,
            subject = request.subject,
            body = request.body,
            emailConfigId = request.emailConfigId,
            ccEmails = request.ccEmails,
            bccEmails = request.bccEmails,
            status = request.status
        )

        val saved = emailTemplateRepository.save(emailTemplate)
        logger.info("Email template created with ID: ${saved.id}")

        return toResponse(saved, tenantId)
    }

    /**
     * Get all email templates for a tenant
     */
    fun getAllEmailTemplates(tenantId: Int): EmailTemplateListResponse {
        logger.debug("Fetching all email templates for tenant: $tenantId")

        val templates = emailTemplateRepository.findByTenantId(tenantId)

        val items = templates.map { template ->
            val emailConfigName = getEmailConfigName(template.emailConfigId, tenantId)
            EmailTemplateListItem(
                id = template.id,
                templateName = template.templateName,
                templateType = template.templateType,
                emailConfigName = emailConfigName,
                status = template.status
            )
        }

        return EmailTemplateListResponse(data = items)
    }

    /**
     * Get email template by ID
     */
    fun getEmailTemplateById(tenantId: Int, templateId: String): EmailTemplateResponse {
        logger.debug("Fetching email template: $templateId for tenant: $tenantId")

        val template = emailTemplateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow { IllegalArgumentException("Email template not found with ID: $templateId") }

        return toResponse(template, tenantId)
    }

    /**
     * Update email template
     */
    @Transactional
    fun updateEmailTemplate(tenantId: Int, templateId: String, request: UpdateEmailTemplateRequest): EmailTemplateResponse {
        logger.info("Updating email template: $templateId for tenant: $tenantId")

        val existing = emailTemplateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow { IllegalArgumentException("Email template not found with ID: $templateId") }

        // Check if template name is being changed and if it conflicts
        if (request.templateName != existing.templateName) {
            if (emailTemplateRepository.existsByTemplateNameAndTenantId(request.templateName, tenantId)) {
                throw IllegalArgumentException("Email template with name '${request.templateName}' already exists")
            }
        }

        val updated = existing.copy(
            templateName = request.templateName,
            templateType = request.templateType,
            subject = request.subject,
            body = request.body,
            emailConfigId = request.emailConfigId,
            ccEmails = request.ccEmails,
            bccEmails = request.bccEmails,
            status = request.status
        )

        val saved = emailTemplateRepository.save(updated)
        logger.info("Email template updated: ${saved.id}")

        return toResponse(saved, tenantId)
    }

    /**
     * Delete email template
     */
    @Transactional
    fun deleteEmailTemplate(tenantId: Int, templateId: String) {
        logger.info("Deleting email template: $templateId for tenant: $tenantId")

        // Verify template exists and belongs to tenant
        emailTemplateRepository.findByIdAndTenantId(templateId, tenantId)
            .orElseThrow { IllegalArgumentException("Email template not found with ID: $templateId") }

        // Delete the template
        emailTemplateRepository.deleteById(templateId)
        logger.info("Email template deleted: $templateId")
    }

    /**
     * Convert EmailTemplate entity to response DTO
     */
    private fun toResponse(template: EmailTemplate, tenantId: Int): EmailTemplateResponse {
        val emailConfigName = getEmailConfigName(template.emailConfigId, tenantId)

        return EmailTemplateResponse(
            id = template.id,
            templateName = template.templateName,
            templateType = template.templateType,
            subject = template.subject,
            body = template.body,
            emailConfigId = template.emailConfigId,
            emailConfigName = emailConfigName,
            ccEmails = template.ccEmails,
            bccEmails = template.bccEmails,
            status = template.status,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt
        )
    }

    /**
     * Get email config name by ID
     */
    private fun getEmailConfigName(configId: String?, tenantId: Int): String? {
        if (configId.isNullOrBlank()) return null

        return try {
            emailConfigRepository.findByIdAndTenantId(configId, tenantId)
                .map { it.connectionName }
                .orElse(null)
        } catch (e: Exception) {
            logger.warn("Failed to fetch email config name for ID: $configId", e)
            null
        }
    }
}
