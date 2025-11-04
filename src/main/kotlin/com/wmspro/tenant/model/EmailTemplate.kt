package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime
import java.util.UUID
import jakarta.validation.constraints.*

/**
 * EmailTemplate Model - Stores email templates for different document types
 * Collection: email_templates
 * Database: Central shared database (NOT tenant-specific)
 */
@Document(collection = "email_templates")
@CompoundIndexes(
    CompoundIndex(name = "tenant_name_idx", def = "{'tenantId': 1, 'templateName': 1}", unique = true),
    CompoundIndex(name = "tenant_type_idx", def = "{'tenantId': 1, 'templateType': 1}")
)
data class EmailTemplate(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Indexed
    val tenantId: Int,

    @field:NotBlank(message = "Template name cannot be blank")
    val templateName: String,

    @field:NotNull(message = "Template type cannot be null")
    val templateType: EmailTemplateType,

    @field:NotBlank(message = "Subject cannot be blank")
    val subject: String,

    @field:NotBlank(message = "Body cannot be blank")
    val body: String, // HTML or plain text content

    val emailConfigId: String? = null, // UUID reference to EmailConfig

    val ccEmails: List<String> = emptyList(),

    val bccEmails: List<String> = emptyList(),

    val status: EmailTemplateStatus = EmailTemplateStatus.ACTIVE,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(tenantId > 0) { "Tenant ID must be positive" }
        require(templateName.isNotBlank()) { "Template name cannot be blank" }
        require(subject.isNotBlank()) { "Subject cannot be blank" }
        require(body.isNotBlank()) { "Body cannot be blank" }
    }
}
