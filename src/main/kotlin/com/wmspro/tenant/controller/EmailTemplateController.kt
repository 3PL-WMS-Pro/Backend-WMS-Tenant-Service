package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.service.EmailTemplateService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for Email Template operations
 */
@RestController
@RequestMapping("/api/v1/email-templates")
@Tag(name = "Email Template", description = "APIs for managing email templates")
class EmailTemplateController(
    private val emailTemplateService: EmailTemplateService
) {
    private val logger = LoggerFactory.getLogger(EmailTemplateController::class.java)

    /**
     * Create new email template
     */
    @PostMapping
    @Operation(
        summary = "Create Email Template",
        description = "Create a new email template for the tenant"
    )
    fun createEmailTemplate(
        @Valid @RequestBody request: CreateEmailTemplateRequest
    ): ResponseEntity<ApiResponse<EmailTemplateResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Creating email template for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailTemplateService.createEmailTemplate(tenantId.toInt(), request)

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = response,
                    message = "Email template created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid email template creation request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            logger.error("Error creating email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to create email template")
            )
        }
    }

    /**
     * Get all email templates
     */
    @GetMapping
    @Operation(
        summary = "Get All Email Templates",
        description = "Retrieve all email templates for the tenant"
    )
    fun getAllEmailTemplates(): ResponseEntity<ApiResponse<EmailTemplateListResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email templates for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailTemplateService.getAllEmailTemplates(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email templates retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching email templates", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to retrieve email templates")
            )
        }
    }

    /**
     * Get email template by ID
     */
    @GetMapping("/{templateId}")
    @Operation(
        summary = "Get Email Template",
        description = "Retrieve a specific email template by ID"
    )
    fun getEmailTemplateById(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<EmailTemplateResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching email template: $templateId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailTemplateService.getEmailTemplateById(tenantId.toInt(), templateId)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email template retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Email template not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Email template not found")
            )
        } catch (e: Exception) {
            logger.error("Error fetching email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to retrieve email template")
            )
        }
    }

    /**
     * Update email template
     */
    @PutMapping("/{templateId}")
    @Operation(
        summary = "Update Email Template",
        description = "Update an existing email template"
    )
    fun updateEmailTemplate(
        @PathVariable templateId: String,
        @Valid @RequestBody request: UpdateEmailTemplateRequest
    ): ResponseEntity<ApiResponse<EmailTemplateResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating email template: $templateId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = emailTemplateService.updateEmailTemplate(tenantId.toInt(), templateId, request)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Email template updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid email template update: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            logger.error("Error updating email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to update email template")
            )
        }
    }

    /**
     * Delete email template
     */
    @DeleteMapping("/{templateId}")
    @Operation(
        summary = "Delete Email Template",
        description = "Delete an email template"
    )
    fun deleteEmailTemplate(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<Unit>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Deleting email template: $templateId for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            emailTemplateService.deleteEmailTemplate(tenantId.toInt(), templateId)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "Email template deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Email template not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Email template not found")
            )
        } catch (e: Exception) {
            logger.error("Error deleting email template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to delete email template")
            )
        }
    }
}
