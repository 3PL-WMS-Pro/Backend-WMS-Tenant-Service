package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.exception.NotFoundException
import com.wmspro.tenant.model.EmailConfig
import com.wmspro.tenant.model.EmailTemplate
import com.wmspro.tenant.model.SlaSettings
import com.wmspro.tenant.model.TenantSettings
import com.wmspro.tenant.service.TenantSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

/**
 * Controller for tenant settings operations
 * Implements APIs 067, 068 from API Specsheet
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tenant Settings", description = "APIs for managing tenant configuration settings")
class TenantSettingsController(
    private val tenantSettingsService: TenantSettingsService
) {
    private val logger = LoggerFactory.getLogger(TenantSettingsController::class.java)

    /**
     * API 067: Get All Tenant Settings
     * Retrieves all settings for the current tenant based on authenticated context
     */
    @GetMapping("/tenant-settings")
    @Operation(
        summary = "Get All Tenant Settings",
        description = "Retrieves all settings for the current tenant based on the authenticated tenant context"
    )
    fun getTenantSettings(): ResponseEntity<ApiResponse<TenantSettingsResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching tenant settings for tenant: $tenantId")

        try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<TenantSettingsResponse>(
                        message = "Tenant context missing or invalid"
                    )
                )
            }

            val settings = tenantSettingsService.getTenantSettings(tenantId.toInt())
            return if (settings != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = settings,
                        message = "Tenant settings retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<TenantSettingsResponse>(
                        message = "Tenant not found"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching tenant settings", e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<TenantSettingsResponse>(
                    message = "Failed to retrieve tenant settings"
                )
            )
        }
    }

    /**
     * API 068: Update Tenant Settings
     * Updates configuration settings for the current tenant
     */
    @PutMapping("/tenant-settings")
    @Operation(
        summary = "Update Tenant Settings",
        description = "Updates configuration settings for the current tenant"
    )
    fun updateTenantSettings(
        @Valid @RequestBody request: UpdateTenantSettingsRequest
    ): ResponseEntity<ApiResponse<TenantSettings>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating tenant settings for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<TenantSettings>(
                        message = "Tenant context missing or invalid"
                    )
                )
            }

            val updatedSettings = tenantSettingsService.updateTenantSettings(
                tenantId.toInt(),
                request.tenantSettings
            )

            ResponseEntity.ok(
                ApiResponse.success(
                    data = updatedSettings,
                    message = "Tenant settings updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid tenant settings update request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<TenantSettings>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<TenantSettings>(
                    message = "Tenant not found"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating tenant settings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<TenantSettings>(
                    message = "Failed to update tenant settings"
                )
            )
        }
    }

    // ==================== SLA Settings Endpoints ====================

    /**
     * Get Task SLA Settings
     */
    @GetMapping("/tenant-settings/sla")
    @Operation(
        summary = "Get Task SLA Settings",
        description = "Retrieves SLA (Service Level Agreement) settings for task operations"
    )
    fun getSlaSettings(): ResponseEntity<ApiResponse<SlaSettingsResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching SLA settings for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val response = tenantSettingsService.getSlaSettings(tenantId.toInt())
            ResponseEntity.ok(ApiResponse.success(data = response, message = "SLA settings retrieved successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: Exception) {
            logger.error("Error fetching SLA settings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to retrieve SLA settings"))
        }
    }

    /**
     * Update Task SLA Settings
     */
    @PutMapping("/tenant-settings/sla")
    @Operation(
        summary = "Update Task SLA Settings",
        description = "Updates SLA settings for task operations. Supports partial updates - only provided fields will be updated."
    )
    fun updateSlaSettings(
        @Valid @RequestBody request: UpdateSlaSettingsRequest
    ): ResponseEntity<ApiResponse<SlaSettings>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating SLA settings for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val updated = tenantSettingsService.updateSlaSettings(tenantId.toInt(), request)
            ResponseEntity.ok(ApiResponse.success(data = updated, message = "SLA settings updated successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ApiResponse.error(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            logger.error("Error updating SLA settings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to update SLA settings"))
        }
    }

    // ==================== Email Config Endpoints ====================

    /**
     * Get Email Config List
     */
    @GetMapping("/tenant-settings/email-configs")
    @Operation(
        summary = "Get Email Config List",
        description = "Retrieves all email configurations for the tenant"
    )
    fun getEmailConfigs(): ResponseEntity<ApiResponse<EmailConfigListResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email configs for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val response = tenantSettingsService.getEmailConfigs(tenantId.toInt())
            ResponseEntity.ok(ApiResponse.success(data = response, message = "Email configs retrieved successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: Exception) {
            logger.error("Error fetching email configs", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to retrieve email configs"))
        }
    }

    /**
     * Get Email Config by Key
     */
    @GetMapping("/tenant-settings/email-configs/{configKey}")
    @Operation(
        summary = "Get Email Config by Key",
        description = "Retrieves a specific email configuration by its key"
    )
    fun getEmailConfigByKey(
        @PathVariable configKey: String
    ): ResponseEntity<ApiResponse<EmailConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email config '$configKey' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val response = tenantSettingsService.getEmailConfigByKey(tenantId.toInt(), configKey)
            ResponseEntity.ok(ApiResponse.success(data = response, message = "Email config retrieved successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Email config not found"))
        } catch (e: Exception) {
            logger.error("Error fetching email config", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to retrieve email config"))
        }
    }

    /**
     * Add Email Config
     */
    @PostMapping("/tenant-settings/email-configs")
    @Operation(
        summary = "Add Email Config",
        description = "Adds a new email configuration with a unique key"
    )
    fun addEmailConfig(
        @Valid @RequestBody request: EmailConfigRequest
    ): ResponseEntity<ApiResponse<EmailConfig>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Adding email config '${request.configKey}' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val created = tenantSettingsService.addEmailConfig(tenantId.toInt(), request)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(data = created, message = "Email config added successfully")
            )
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(message = e.message ?: "Email config already exists"))
        } catch (e: Exception) {
            logger.error("Error adding email config", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to add email config"))
        }
    }

    /**
     * Update Email Config
     */
    @PutMapping("/tenant-settings/email-configs/{configKey}")
    @Operation(
        summary = "Update Email Config",
        description = "Updates an existing email configuration"
    )
    fun updateEmailConfig(
        @PathVariable configKey: String,
        @Valid @RequestBody request: EmailConfigRequest
    ): ResponseEntity<ApiResponse<EmailConfig>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating email config '$configKey' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val updated = tenantSettingsService.updateEmailConfig(tenantId.toInt(), configKey, request)
            ResponseEntity.ok(ApiResponse.success(data = updated, message = "Email config updated successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Email config not found"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ApiResponse.error(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            logger.error("Error updating email config", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to update email config"))
        }
    }

    /**
     * Delete Email Config
     */
    @DeleteMapping("/tenant-settings/email-configs/{configKey}")
    @Operation(
        summary = "Delete Email Config",
        description = "Deletes an email configuration by its key"
    )
    fun deleteEmailConfig(
        @PathVariable configKey: String
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Deleting email config '$configKey' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            tenantSettingsService.deleteEmailConfig(tenantId.toInt(), configKey)
            ResponseEntity.ok(ApiResponse.success(
                data = mapOf("deleted" to true, "configKey" to configKey),
                message = "Email config deleted successfully"
            ))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Email config not found"))
        } catch (e: Exception) {
            logger.error("Error deleting email config", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to delete email config"))
        }
    }

    // ==================== Email Template Endpoints ====================

    /**
     * Get All Email Templates
     */
    @GetMapping("/tenant-settings/email-templates")
    @Operation(
        summary = "Get Email Templates",
        description = "Retrieves all email templates for the tenant"
    )
    fun getEmailTemplates(): ResponseEntity<ApiResponse<EmailTemplatesResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email templates for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val response = tenantSettingsService.getEmailTemplates(tenantId.toInt())
            ResponseEntity.ok(ApiResponse.success(data = response, message = "Email templates retrieved successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: Exception) {
            logger.error("Error fetching email templates", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to retrieve email templates"))
        }
    }

    /**
     * Get Email Template by Type
     */
    @GetMapping("/tenant-settings/email-templates/{templateType}")
    @Operation(
        summary = "Get Specific Email Template",
        description = "Retrieves a specific email template by type (GRN, GIN, INVOICE, PACKING_LIST, DELIVERY_NOTE)"
    )
    fun getEmailTemplateByType(
        @PathVariable templateType: EmailTemplateType
    ): ResponseEntity<ApiResponse<EmailTemplateResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email template '$templateType' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val response = tenantSettingsService.getEmailTemplateByType(tenantId.toInt(), templateType)
            ResponseEntity.ok(ApiResponse.success(data = response, message = "Email template retrieved successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: Exception) {
            logger.error("Error fetching email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to retrieve email template"))
        }
    }

    /**
     * Set Email Template
     */
    @PutMapping("/tenant-settings/email-templates/{templateType}")
    @Operation(
        summary = "Set Email Template",
        description = "Sets (creates or overwrites) an email template for a specific type"
    )
    fun setEmailTemplate(
        @PathVariable templateType: EmailTemplateType,
        @Valid @RequestBody request: SetEmailTemplateRequest
    ): ResponseEntity<ApiResponse<EmailTemplate>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Setting email template '$templateType' for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error(message = "Tenant context missing or invalid")
                )
            }

            val template = tenantSettingsService.setEmailTemplate(tenantId.toInt(), templateType, request)
            ResponseEntity.ok(ApiResponse.success(data = template, message = "Email template set successfully"))
        } catch (e: NotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(message = e.message ?: "Tenant not found"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(ApiResponse.error(message = e.message ?: "Invalid request"))
        } catch (e: Exception) {
            logger.error("Error setting email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(message = "Failed to set email template"))
        }
    }
}