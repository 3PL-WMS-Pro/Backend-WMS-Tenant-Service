package com.wmspro.tenant.repository

import com.wmspro.tenant.model.EmailTemplate
import com.wmspro.tenant.model.EmailTemplateType
import com.wmspro.tenant.model.EmailTemplateStatus
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for EmailTemplate operations
 */
@Repository
interface EmailTemplateRepository : MongoRepository<EmailTemplate, String> {

    /**
     * Find all email templates for a specific tenant
     */
    fun findByTenantId(tenantId: Int): List<EmailTemplate>

    /**
     * Find email template by ID and tenant ID
     */
    fun findByIdAndTenantId(id: String, tenantId: Int): Optional<EmailTemplate>

    /**
     * Find by template name and tenant ID
     */
    fun findByTemplateNameAndTenantId(templateName: String, tenantId: Int): Optional<EmailTemplate>

    /**
     * Find templates by type and tenant ID
     */
    fun findByTenantIdAndTemplateType(tenantId: Int, templateType: EmailTemplateType): List<EmailTemplate>

    /**
     * Find all active email templates for a tenant
     */
    fun findByTenantIdAndStatus(tenantId: Int, status: EmailTemplateStatus): List<EmailTemplate>

    /**
     * Find templates by email config ID
     */
    fun findByEmailConfigId(emailConfigId: String): List<EmailTemplate>

    /**
     * Check if template name exists for a tenant
     */
    fun existsByTemplateNameAndTenantId(templateName: String, tenantId: Int): Boolean

    /**
     * Delete by ID and tenant ID
     */
    fun deleteByIdAndTenantId(id: String, tenantId: Int): Long
}
