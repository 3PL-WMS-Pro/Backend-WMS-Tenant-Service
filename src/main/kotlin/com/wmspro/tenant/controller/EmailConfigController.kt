package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.service.EmailConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for Email Configuration operations
 */
@RestController
@RequestMapping("/api/v1/email-configs")
@Tag(name = "Email Configuration", description = "APIs for managing SMTP email configurations")
class EmailConfigController(
    private val emailConfigService: EmailConfigService
) {
    private val logger = LoggerFactory.getLogger(EmailConfigController::class.java)

    /**
     * Create new email configuration
     */
    @PostMapping
    @Operation(
        summary = "Create Email Configuration",
        description = "Create a new SMTP email configuration for the tenant"
    )
    fun createEmailConfig(
        @Valid @RequestBody request: CreateEmailConfigRequest
    ): ResponseEntity<ApiResponse<EmailConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Creating email config for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailConfigService.createEmailConfig(tenantId.toInt(), request)

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = response,
                    message = "Email configuration created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid email config creation request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            logger.error("Error creating email configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to create email configuration")
            )
        }
    }

    /**
     * Get all email configurations
     */
    @GetMapping
    @Operation(
        summary = "Get All Email Configurations",
        description = "Retrieve all email configurations for the tenant"
    )
    fun getAllEmailConfigs(): ResponseEntity<ApiResponse<EmailConfigListResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email configs for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailConfigService.getAllEmailConfigs(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email configurations retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching email configurations", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to retrieve email configurations")
            )
        }
    }

    /**
     * Get email configuration by ID
     */
    @GetMapping("/{configId}")
    @Operation(
        summary = "Get Email Configuration",
        description = "Retrieve a specific email configuration by ID"
    )
    fun getEmailConfigById(
        @PathVariable configId: String
    ): ResponseEntity<ApiResponse<EmailConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email config: $configId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailConfigService.getEmailConfigById(tenantId.toInt(), configId)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email configuration retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Email config not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Email configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error fetching email configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to retrieve email configuration")
            )
        }
    }

    /**
     * Update email configuration
     */
    @PutMapping("/{configId}")
    @Operation(
        summary = "Update Email Configuration",
        description = "Update an existing email configuration"
    )
    fun updateEmailConfig(
        @PathVariable configId: String,
        @Valid @RequestBody request: UpdateEmailConfigRequest
    ): ResponseEntity<ApiResponse<EmailConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating email config: $configId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailConfigService.updateEmailConfig(tenantId.toInt(), configId, request)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email configuration updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid email config update: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            logger.error("Error updating email configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to update email configuration")
            )
        }
    }

    /**
     * Delete email configuration
     */
    @DeleteMapping("/{configId}")
    @Operation(
        summary = "Delete Email Configuration",
        description = "Delete an email configuration and remove references from templates"
    )
    fun deleteEmailConfig(
        @PathVariable configId: String
    ): ResponseEntity<ApiResponse<Unit>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Deleting email config: $configId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            emailConfigService.deleteEmailConfig(tenantId.toInt(), configId)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "Email configuration deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Email config not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Email configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error deleting email configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to delete email configuration")
            )
        }
    }
}
