package com.wmspro.tenant.dto

import com.wmspro.tenant.model.EmailConfig
import com.wmspro.tenant.model.EmailTemplate
import com.wmspro.tenant.model.SlaSettings
import com.wmspro.tenant.model.TenantSettings
import jakarta.validation.constraints.*
import java.time.LocalDateTime

/**
 * DTOs for Tenant Settings operations
 */

/**
 * Response for getting tenant settings
 */
data class TenantSettingsResponse(
    val clientId: Int,
    val tenantSettings: TenantSettings,
    val lastModified: LocalDateTime?,
    val settingsCount: Int,
    val categories: List<String>
)

/**
 * Request for updating tenant settings
 */
data class UpdateTenantSettingsRequest(
    val tenantSettings: TenantSettings,
    val merge: Boolean = true // If true, merge with existing settings. If false, replace.
)

// ==================== SLA Settings DTOs ====================

/**
 * Response for SLA settings
 */
data class SlaSettingsResponse(
    val slaSettings: SlaSettings,
    val lastModified: LocalDateTime?
)

/**
 * Request for updating SLA settings
 */
data class UpdateSlaSettingsRequest(
    @field:Min(1, message = "Counting SLA must be at least 1 minute")
    @field:Max(10080, message = "Counting SLA cannot exceed 10080 minutes (1 week)")
    val countingSlaMinutes: Int? = null,

    @field:Min(1, message = "Transfer SLA must be at least 1 minute")
    @field:Max(10080, message = "Transfer SLA cannot exceed 10080 minutes (1 week)")
    val transferSlaMinutes: Int? = null,

    @field:Min(1, message = "Offloading SLA must be at least 1 minute")
    @field:Max(10080, message = "Offloading SLA cannot exceed 10080 minutes (1 week)")
    val offloadingSlaMinutes: Int? = null,

    @field:Min(1, message = "Receiving SLA must be at least 1 minute")
    @field:Max(10080, message = "Receiving SLA cannot exceed 10080 minutes (1 week)")
    val receivingSlaMinutes: Int? = null,

    @field:Min(1, message = "Putaway SLA must be at least 1 minute")
    @field:Max(10080, message = "Putaway SLA cannot exceed 10080 minutes (1 week)")
    val putawaySlaMinutes: Int? = null,

    @field:Min(1, message = "Picking SLA must be at least 1 minute")
    @field:Max(10080, message = "Picking SLA cannot exceed 10080 minutes (1 week)")
    val pickingSlaMinutes: Int? = null,

    @field:Min(1, message = "Pack/Move SLA must be at least 1 minute")
    @field:Max(10080, message = "Pack/Move SLA cannot exceed 10080 minutes (1 week)")
    val packMoveSlaMinutes: Int? = null,

    @field:Min(1, message = "Pick/Pack/Move SLA must be at least 1 minute")
    @field:Max(10080, message = "Pick/Pack/Move SLA cannot exceed 10080 minutes (1 week)")
    val pickPackMoveSlaMinutes: Int? = null,

    @field:Min(1, message = "Loading SLA must be at least 1 minute")
    @field:Max(10080, message = "Loading SLA cannot exceed 10080 minutes (1 week)")
    val loadingSlaMinutes: Int? = null,

    @field:Min(1, message = "Escalation time must be at least 1 minute")
    @field:Max(10080, message = "Escalation time cannot exceed 10080 minutes (1 week)")
    val escalationAfterMinutes: Int? = null
)

// ==================== Email Config DTOs ====================

/**
 * Response for email config list
 */
data class EmailConfigListResponse(
    val configs: Map<String, EmailConfig>,
    val count: Int,
    val lastModified: LocalDateTime?
)

/**
 * Response for single email config
 */
data class EmailConfigResponse(
    val configKey: String,
    val config: EmailConfig,
    val lastModified: LocalDateTime?
)

/**
 * Request for adding/updating email config
 */
data class EmailConfigRequest(
    @field:NotBlank(message = "Config key cannot be blank")
    val configKey: String,

    @field:NotBlank(message = "SMTP host cannot be blank")
    val smtpHost: String,

    @field:Min(1, message = "SMTP port must be positive")
    @field:Max(65535, message = "SMTP port must be <= 65535")
    val smtpPort: Int = 587,

    @field:NotBlank(message = "Username cannot be blank")
    @field:Email(message = "Username must be a valid email")
    val username: String,

    @field:NotBlank(message = "Password cannot be blank")
    val password: String,

    @field:NotBlank(message = "From email cannot be blank")
    @field:Email(message = "From email must be valid")
    val fromEmail: String,

    val fromName: String? = null,

    val useTLS: Boolean = true,
    val useSSL: Boolean = false,
    val authEnabled: Boolean = true
) {
    fun toEmailConfig(): EmailConfig = EmailConfig(
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        username = username,
        password = password,
        fromEmail = fromEmail,
        fromName = fromName,
        useTLS = useTLS,
        useSSL = useSSL,
        authEnabled = authEnabled
    )
}

// ==================== Email Template DTOs ====================

/**
 * Enum for email template types
 */
enum class EmailTemplateType {
    GRN,
    GIN,
    INVOICE,
    PACKING_LIST,
    DELIVERY_NOTE
}

/**
 * Response for all email templates
 */
data class EmailTemplatesResponse(
    val templates: Map<EmailTemplateType, EmailTemplate?>,
    val configuredCount: Int,
    val lastModified: LocalDateTime?
)

/**
 * Response for single email template
 */
data class EmailTemplateResponse(
    val templateType: EmailTemplateType,
    val template: EmailTemplate?,
    val isConfigured: Boolean,
    val lastModified: LocalDateTime?
)

/**
 * Request for setting email template
 */
data class SetEmailTemplateRequest(
    @field:NotBlank(message = "Subject cannot be blank")
    val subject: String,

    @field:NotBlank(message = "Body cannot be blank")
    val body: String,

    val emailConfigKey: String = "default",

    val ccEmails: List<@Email(message = "CC email must be valid") String> = emptyList(),

    val bccEmails: List<@Email(message = "BCC email must be valid") String> = emptyList()
) {
    fun toEmailTemplate(): EmailTemplate = EmailTemplate(
        subject = subject,
        body = body,
        emailConfigKey = emailConfigKey,
        ccEmails = ccEmails,
        bccEmails = bccEmails
    )
}

// ==================== S3 Configuration DTOs ====================

/**
 * Response for S3 configuration (masks sensitive credentials)
 */
data class S3ConfigurationResponse(
    val bucketName: String,
    val region: String,
    val accessKeyMasked: String, // Shows only last 4 characters
    val secretKeyMasked: String, // Shows only last 4 characters
    val bucketPrefix: String?,
    val isConfigured: Boolean,
    val lastModified: java.time.LocalDateTime?
)

/**
 * Request for setting S3 configuration
 */
data class SetS3ConfigurationRequest(
    @field:NotBlank(message = "Bucket name cannot be blank")
    val bucketName: String,

    @field:NotBlank(message = "Region cannot be blank")
    val region: String = "ap-south-1",

    @field:NotBlank(message = "Access key cannot be blank")
    val accessKey: String,

    @field:NotBlank(message = "Secret key cannot be blank")
    val secretKey: String,

    val bucketPrefix: String? = null
)