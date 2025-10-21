package com.wmspro.tenant.dto

import com.wmspro.tenant.model.CommonConfig
import com.wmspro.tenant.model.DocumentType
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * Request DTO for creating/updating document template
 */
data class DocumentTemplateRequest(
    @field:NotBlank(message = "Template name cannot be blank")
    val templateName: String,

    val documentType: DocumentType,

    val templateVersion: String = "1.0",

    @field:NotBlank(message = "HTML template cannot be blank")
    val htmlTemplate: String,

    val cssContent: String? = null,

    val commonConfig: CommonConfig = CommonConfig(),

    val documentConfig: Map<String, Any> = emptyMap(),

    val isActive: Boolean = true,

    val isDefault: Boolean = false
)

/**
 * Response DTO for document template
 */
data class DocumentTemplateResponse(
    val templateId: String?,
    val documentType: DocumentType,
    val templateName: String,
    val templateVersion: String,
    val htmlTemplate: String,
    val cssContent: String?,
    val commonConfig: CommonConfig,
    val documentConfig: Map<String, Any>,
    val isActive: Boolean,
    val isDefault: Boolean,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)
