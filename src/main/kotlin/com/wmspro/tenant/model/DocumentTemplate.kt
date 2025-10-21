package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * DocumentTemplate Model - Stores document templates for GRN, GIN, Invoice, etc.
 * Collection: document_templates
 * Database: Central shared database (NOT tenant-specific)
 * Each tenant can have custom templates per document type
 *
 * tenantId = null: Global default template (used by all tenants without custom templates)
 * tenantId = <number>: Tenant-specific custom template (overrides global default)
 */
@Document(collection = "document_templates")
@CompoundIndex(name = "tenant_document_type_active_idx", def = "{'tenantId': 1, 'documentType': 1, 'isActive': 1}")
@CompoundIndex(name = "document_type_active_idx", def = "{'documentType': 1, 'isActive': 1}")
data class DocumentTemplate(
    @Id
    val templateId: String? = null,

    // null = global default template, otherwise tenant-specific
    @Indexed
    val tenantId: Int? = null,

    @Indexed
    val documentType: DocumentType,

    @field:NotBlank(message = "Template name cannot be blank")
    val templateName: String,

    val templateVersion: String = "1.0",

    // HTML template content (Thymeleaf syntax)
    @field:NotBlank(message = "HTML template cannot be blank")
    val htmlTemplate: String,

    // CSS styling (optional, can be embedded in HTML)
    val cssContent: String? = null,

    // Common configuration for ALL document types
    val commonConfig: CommonConfig = CommonConfig(),

    // Document-specific configuration (flexible JSON)
    // For GRN: {"showVehicleNo": true, "showDriverName": true, "showCBM": true, ...}
    // For GIN: {"showDeliveryAddress": true, "showCarrier": true, ...}
    // For Invoice: {"showTaxAmount": true, "showDiscount": true, ...}
    val documentConfig: Map<String, Any> = emptyMap(),

    val isActive: Boolean = true,

    // Default template is used when no custom template exists
    val isDefault: Boolean = false,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
)

/**
 * Common configuration shared across all document types
 */
data class CommonConfig(
    // Branding
    val logoUrl: String? = null,
    val companyName: String? = null,
    val primaryColor: String = "#000000",
    val secondaryColor: String = "#666666",
    val fontFamily: String = "Arial, sans-serif",

    // Page settings
    val pageSize: String = "A4", // A4, Letter, Legal
    val orientation: String = "portrait", // portrait, landscape
    val margins: PageMargins = PageMargins()
)

/**
 * Page margin configuration
 */
data class PageMargins(
    val top: String = "20mm",
    val right: String = "15mm",
    val bottom: String = "20mm",
    val left: String = "15mm"
)

/**
 * Document types supported by the system
 */
enum class DocumentType {
    GRN,
    GIN,
    INVOICE,
    PACKING_LIST,
    DELIVERY_NOTE,
    STOCK_TRANSFER_NOTE
}
