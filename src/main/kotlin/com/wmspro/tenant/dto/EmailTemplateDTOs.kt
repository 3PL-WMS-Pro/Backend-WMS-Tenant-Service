package com.wmspro.tenant.dto

import com.wmspro.tenant.model.EmailTemplateType
import com.wmspro.tenant.model.EmailTemplateStatus
import java.time.LocalDateTime
import jakarta.validation.constraints.*

/**
 * DTOs for Email Template operations
 */

/**
 * Request for creating email template
 */
data class CreateEmailTemplateRequest(
    @field:NotBlank(message = "Template name cannot be blank")
    val templateName: String,

    @field:NotNull(message = "Template type cannot be null")
    val templateType: EmailTemplateType,

    @field:NotBlank(message = "Subject cannot be blank")
    val subject: String,

    @field:NotBlank(message = "Body cannot be blank")
    val body: String,

    val emailConfigId: String? = null,

    val ccEmails: List<String> = emptyList(),

    val bccEmails: List<String> = emptyList(),

    val status: EmailTemplateStatus = EmailTemplateStatus.ACTIVE
)

/**
 * Request for updating email template
 */
data class UpdateEmailTemplateRequest(
    @field:NotBlank(message = "Template name cannot be blank")
    val templateName: String,

    @field:NotNull(message = "Template type cannot be null")
    val templateType: EmailTemplateType,

    @field:NotBlank(message = "Subject cannot be blank")
    val subject: String,

    @field:NotBlank(message = "Body cannot be blank")
    val body: String,

    val emailConfigId: String? = null,

    val ccEmails: List<String> = emptyList(),

    val bccEmails: List<String> = emptyList(),

    val status: EmailTemplateStatus = EmailTemplateStatus.ACTIVE
)

/**
 * Response for email template (single)
 */
data class EmailTemplateResponse(
    val id: String,
    val templateName: String,
    val templateType: EmailTemplateType,
    val subject: String,
    val body: String,
    val emailConfigId: String?,
    val emailConfigName: String?, // Populated from emailConfig if available
    val ccEmails: List<String>,
    val bccEmails: List<String>,
    val status: EmailTemplateStatus,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * List item for email templates
 */
data class EmailTemplateListItem(
    val id: String,
    val templateName: String,
    val templateType: EmailTemplateType,
    val emailConfigName: String?,
    val status: EmailTemplateStatus
)

/**
 * Response for list of email templates
 */
data class EmailTemplateListResponse(
    val data: List<EmailTemplateListItem>
)
