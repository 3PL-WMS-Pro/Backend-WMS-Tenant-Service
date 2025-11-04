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
 * EmailConfig Model - Stores SMTP email configuration for each tenant
 * Collection: email_configs
 * Database: Central shared database (NOT tenant-specific)
 */
@Document(collection = "email_configs")
@CompoundIndexes(
    CompoundIndex(name = "tenant_connection_idx", def = "{'tenantId': 1, 'connectionName': 1}", unique = true)
)
data class EmailConfig(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Indexed
    val tenantId: Int,

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
    val password: String, // Stored encrypted

    @field:NotBlank(message = "From email cannot be blank")
    @field:Email(message = "From email must be valid")
    val fromEmail: String,

    val fromName: String? = null,

    val useTLS: Boolean = true,

    val useSSL: Boolean = false,

    val authEnabled: Boolean = true,

    val status: EmailConfigStatus = EmailConfigStatus.ACTIVE,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(tenantId > 0) { "Tenant ID must be positive" }
        require(connectionName.isNotBlank()) { "Connection name cannot be blank" }
        require(smtpHost.isNotBlank()) { "SMTP host cannot be blank" }
        require(smtpPort in 1..65535) { "SMTP port must be between 1 and 65535" }
        require(username.isNotBlank()) { "Username cannot be blank" }
        require(password.isNotBlank()) { "Password cannot be blank" }
        require(fromEmail.isNotBlank()) { "From email cannot be blank" }
    }
}
