package com.wmspro.tenant.repository

import com.wmspro.tenant.model.DocumentTemplate
import com.wmspro.tenant.model.DocumentType
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

/**
 * Repository interface for DocumentTemplate operations
 * Supports both tenant-specific and global default templates
 */
@Repository
interface DocumentTemplateRepository : MongoRepository<DocumentTemplate, String> {

    // ========== Tenant-Aware Queries (NEW) ==========

    /**
     * Find active template by tenant ID and document type
     * Use for tenant-specific template lookup
     */
    fun findByTenantIdAndDocumentTypeAndIsActiveTrue(
        tenantId: Int?,
        documentType: DocumentType
    ): DocumentTemplate?

    /**
     * Find all templates for a specific tenant and document type
     */
    fun findAllByTenantIdAndDocumentType(
        tenantId: Int?,
        documentType: DocumentType
    ): List<DocumentTemplate>

    /**
     * Find all templates for a specific tenant
     */
    fun findAllByTenantId(tenantId: Int?): List<DocumentTemplate>

    /**
     * Check if a template exists for a specific tenant and document type
     */
    fun existsByTenantIdAndDocumentType(
        tenantId: Int?,
        documentType: DocumentType
    ): Boolean

    // ========== Legacy Queries (for backward compatibility) ==========

    /**
     * Find active template by document type (ignores tenantId - gets first match)
     * DEPRECATED: Use tenant-aware method instead
     */
    fun findByDocumentTypeAndIsActiveTrue(documentType: DocumentType): DocumentTemplate?

    /**
     * Find default template by document type
     */
    fun findByDocumentTypeAndIsDefaultTrue(documentType: DocumentType): DocumentTemplate?

    /**
     * Find all templates by document type (across all tenants)
     */
    fun findAllByDocumentType(documentType: DocumentType): List<DocumentTemplate>

    /**
     * Find all active templates (across all tenants)
     */
    fun findAllByIsActiveTrue(): List<DocumentTemplate>

    /**
     * Check if a default template exists for a document type
     */
    fun existsByDocumentTypeAndIsDefaultTrue(documentType: DocumentType): Boolean
}
