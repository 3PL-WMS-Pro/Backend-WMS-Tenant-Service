package com.wmspro.tenant.dto

import com.wmspro.tenant.model.EmailConfigStatus
import java.time.LocalDateTime
import jakarta.validation.constraints.*

/**
 * DTOs for Email Configuration operations
 */

/**
 * Request for creating email configuration
 */
data class CreateEmailConfigRequest(
    @field:NotBlank(message = "Connection name cannot be blank")
    val connectionName: String,

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

    val authEnabled: Boolean = true,

    val status: EmailConfigStatus = EmailConfigStatus.ACTIVE
)

/**
 * Request for updating email configuration
 */
data class UpdateEmailConfigRequest(
    @field:NotBlank(message = "Connection name cannot be blank")
    val connectionName: String,

    @field:NotBlank(message = "SMTP host cannot be blank")
    val smtpHost: String,

    @field:Min(1, message = "SMTP port must be positive")
    @field:Max(65535, message = "SMTP port must be <= 65535")
    val smtpPort: Int = 587,

    @field:NotBlank(message = "Username cannot be blank")
    @field:Email(message = "Username must be a valid email")
    val username: String,

    val password: String? = null, // Optional on update - if null, keep existing password

    @field:NotBlank(message = "From email cannot be blank")
    @field:Email(message = "From email must be valid")
    val fromEmail: String,

    val fromName: String? = null,

    val useTLS: Boolean = true,

    val useSSL: Boolean = false,

    val authEnabled: Boolean = true,

    val status: EmailConfigStatus = EmailConfigStatus.ACTIVE
)

/**
 * Response for email configuration (single)
 */
data class EmailConfigResponse(
    val id: String,
    val connectionName: String,
    val smtpHost: String,
    val smtpPort: Int,
    val username: String,
    val password: String, // Will be masked in service layer
    val fromEmail: String,
    val fromName: String?,
    val useTLS: Boolean,
    val useSSL: Boolean,
    val authEnabled: Boolean,
    val status: EmailConfigStatus,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * List item for email configurations
 */
data class EmailConfigListItem(
    val id: String,
    val connectionName: String,
    val smtpHost: String,
    val smtpPort: Int,
    val fromEmail: String,
    val status: EmailConfigStatus
)

/**
 * Response for list of email configurations
 */
data class EmailConfigListResponse(
    val data: List<EmailConfigListItem>
)
