package com.wmspro.tenant.service

import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.model.DocumentTemplate
import com.wmspro.tenant.model.DocumentType
import com.wmspro.tenant.repository.DocumentTemplateRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing document templates
 */
@Service
class DocumentTemplateService(
    private val documentTemplateRepository: DocumentTemplateRepository
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Get active template by document type (tenant-aware)
     * Lookup priority:
     * 1. Tenant-specific active template (if tenant context exists)
     * 2. Global default active template (tenantId = null)
     * 3. Global default template with isDefault = true
     */
    fun getActiveTemplate(documentType: DocumentType): DocumentTemplate? {
        logger.debug("Fetching active template for document type: {}", documentType)

        // Get current tenant ID from context (if available)
        val tenantId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.debug("Tenant context: {}", tenantId ?: "null (global)")

        // 1. Try tenant-specific active template first
        if (tenantId != null) {
            val tenantTemplate = documentTemplateRepository.findByTenantIdAndDocumentTypeAndIsActiveTrue(tenantId, documentType)
            if (tenantTemplate != null) {
                logger.debug("Found tenant-specific active template for tenant: {}, document: {}", tenantId, documentType)
                return tenantTemplate
            }
            logger.debug("No tenant-specific template found for tenant: {}, falling back to global", tenantId)
        }

        // 2. Fallback to global default active template (tenantId = null)
        var template = documentTemplateRepository.findByTenantIdAndDocumentTypeAndIsActiveTrue(null, documentType)
        if (template != null) {
            logger.debug("Found global active template for document type: {}", documentType)
            return template
        }

        // 3. Final fallback to global default template with isDefault = true
        logger.debug("No global active template found, falling back to isDefault template for: {}", documentType)
        template = documentTemplateRepository.findByDocumentTypeAndIsDefaultTrue(documentType)

        if (template == null) {
            logger.warn("No template found (tenant, global, or default) for document type: {}", documentType)
        }

        return template
    }

    /**
     * Get template by ID
     */
    fun getTemplateById(templateId: String): DocumentTemplate? {
        logger.debug("Fetching template by ID: {}", templateId)
        return documentTemplateRepository.findById(templateId).orElse(null)
    }

    /**
     * Get all templates by document type
     */
    fun getAllTemplatesByType(documentType: DocumentType): List<DocumentTemplate> {
        logger.debug("Fetching all templates for document type: {}", documentType)
        return documentTemplateRepository.findAllByDocumentType(documentType)
    }

    /**
     * Get all active templates
     */
    fun getAllActiveTemplates(): List<DocumentTemplate> {
        logger.debug("Fetching all active templates")
        return documentTemplateRepository.findAllByIsActiveTrue()
    }

    /**
     * Get all templates
     */
    fun getAllTemplates(): List<DocumentTemplate> {
        logger.debug("Fetching all templates")
        return documentTemplateRepository.findAll()
    }

    /**
     * Create new template
     */
    @Transactional
    fun createTemplate(template: DocumentTemplate): DocumentTemplate {
        logger.info(
            "Creating new template: {} for document type: {}, tenantId: {}",
            template.templateName,
            template.documentType,
            template.tenantId ?: "global"
        )

        // Validate that HTML template is not blank
        require(template.htmlTemplate.isNotBlank()) { "HTML template cannot be blank" }

        // If this is set as default, ensure no other default exists for this document type and tenant
        if (template.isDefault) {
            // For global defaults (tenantId = null), check globally
            // For tenant-specific, this validation is less strict (tenants can have their own defaults)
            if (template.tenantId == null) {
                val existingDefault = documentTemplateRepository.findByDocumentTypeAndIsDefaultTrue(template.documentType)
                if (existingDefault != null && existingDefault.tenantId == null) {
                    throw IllegalStateException(
                        "Global default template already exists for ${template.documentType}. " +
                        "Please deactivate existing default before creating a new one."
                    )
                }
            }
        }

        val savedTemplate = documentTemplateRepository.save(template)
        logger.info("Template created successfully with ID: {}", savedTemplate.templateId)

        return savedTemplate
    }

    /**
     * Update existing template
     */
    @Transactional
    fun updateTemplate(templateId: String, updatedTemplate: DocumentTemplate): DocumentTemplate {
        logger.info("Updating template: {}", templateId)

        val existingTemplate = documentTemplateRepository.findById(templateId).orElse(null)
            ?: throw IllegalArgumentException("Template not found with ID: $templateId")

        // If trying to set as default, check if another default exists
        if (updatedTemplate.isDefault && !existingTemplate.isDefault) {
            val existingDefault = documentTemplateRepository.findByDocumentTypeAndIsDefaultTrue(updatedTemplate.documentType)
            if (existingDefault != null && existingDefault.templateId != templateId) {
                throw IllegalStateException(
                    "Default template already exists for ${updatedTemplate.documentType}. " +
                    "Please deactivate existing default before setting a new one."
                )
            }
        }

        // Create updated template with same ID
        val templateToSave = updatedTemplate.copy(templateId = templateId)
        val savedTemplate = documentTemplateRepository.save(templateToSave)

        logger.info("Template updated successfully: {}", templateId)
        return savedTemplate
    }

    /**
     * Activate a template (set isActive = true)
     */
    @Transactional
    fun activateTemplate(templateId: String): DocumentTemplate {
        logger.info("Activating template: {}", templateId)

        val template = documentTemplateRepository.findById(templateId).orElse(null)
            ?: throw IllegalArgumentException("Template not found with ID: $templateId")

        val updatedTemplate = template.copy(isActive = true)
        val savedTemplate = documentTemplateRepository.save(updatedTemplate)

        logger.info("Template activated successfully: {}", templateId)
        return savedTemplate
    }

    /**
     * Deactivate a template (set isActive = false)
     */
    @Transactional
    fun deactivateTemplate(templateId: String): DocumentTemplate {
        logger.info("Deactivating template: {}", templateId)

        val template = documentTemplateRepository.findById(templateId).orElse(null)
            ?: throw IllegalArgumentException("Template not found with ID: $templateId")

        // Prevent deactivating default template
        if (template.isDefault) {
            throw IllegalStateException("Cannot deactivate default template. Please unset default first.")
        }

        val updatedTemplate = template.copy(isActive = false)
        val savedTemplate = documentTemplateRepository.save(updatedTemplate)

        logger.info("Template deactivated successfully: {}", templateId)
        return savedTemplate
    }

    /**
     * Delete a template
     */
    @Transactional
    fun deleteTemplate(templateId: String) {
        logger.info("Deleting template: {}", templateId)

        val template = documentTemplateRepository.findById(templateId).orElse(null)
            ?: throw IllegalArgumentException("Template not found with ID: $templateId")

        // Prevent deleting default template
        if (template.isDefault) {
            throw IllegalStateException("Cannot delete default template")
        }

        documentTemplateRepository.deleteById(templateId)
        logger.info("Template deleted successfully: {}", templateId)
    }
}
