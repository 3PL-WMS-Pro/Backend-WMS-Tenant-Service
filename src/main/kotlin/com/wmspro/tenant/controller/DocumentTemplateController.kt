package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.DocumentTemplateRequest
import com.wmspro.tenant.dto.DocumentTemplateResponse
import com.wmspro.tenant.model.DocumentTemplate
import com.wmspro.tenant.model.DocumentType
import com.wmspro.tenant.service.DocumentTemplateService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for managing document templates (GRN, GIN, Invoice, etc.)
 */
@RestController
@RequestMapping("/api/v1/document-templates")
class DocumentTemplateController(
    private val documentTemplateService: DocumentTemplateService
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Get active template by document type
     * This is the primary endpoint used by services to fetch templates
     */
    @GetMapping("/active/{documentType}")
    fun getActiveTemplate(
        @PathVariable documentType: DocumentType
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("GET /api/v1/document-templates/active/{}", documentType)

        return try {
            val template = documentTemplateService.getActiveTemplate(documentType)

            if (template != null) {
                ResponseEntity.ok(
                    ApiResponse.success(template.toResponse(), "Template retrieved successfully")
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error("No active or default template found for document type: $documentType")
                )
            }
        } catch (e: Exception) {
            logger.error("Error retrieving active template for document type: $documentType", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Get template by ID
     */
    @GetMapping("/{templateId}")
    fun getTemplateById(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("GET /api/v1/document-templates/{}", templateId)

        return try {
            val template = documentTemplateService.getTemplateById(templateId)

            if (template != null) {
                ResponseEntity.ok(
                    ApiResponse.success(template.toResponse(), "Template retrieved successfully")
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error("Template not found with ID: $templateId")
                )
            }
        } catch (e: Exception) {
            logger.error("Error retrieving template by ID: $templateId", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Get all templates by document type
     */
    @GetMapping("/type/{documentType}")
    fun getAllTemplatesByType(
        @PathVariable documentType: DocumentType
    ): ResponseEntity<ApiResponse<List<DocumentTemplateResponse>>> {
        logger.info("GET /api/v1/document-templates/type/{}", documentType)

        return try {
            val templates = documentTemplateService.getAllTemplatesByType(documentType)
            ResponseEntity.ok(
                ApiResponse.success(
                    templates.map { it.toResponse() },
                    "Templates retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error retrieving templates for document type: $documentType", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Get all active templates
     */
    @GetMapping("/active")
    fun getAllActiveTemplates(): ResponseEntity<ApiResponse<List<DocumentTemplateResponse>>> {
        logger.info("GET /api/v1/document-templates/active")

        return try {
            val templates = documentTemplateService.getAllActiveTemplates()
            ResponseEntity.ok(
                ApiResponse.success(
                    templates.map { it.toResponse() },
                    "Active templates retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error retrieving all active templates", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Get all templates
     */
    @GetMapping
    fun getAllTemplates(): ResponseEntity<ApiResponse<List<DocumentTemplateResponse>>> {
        logger.info("GET /api/v1/document-templates")

        return try {
            val templates = documentTemplateService.getAllTemplates()
            ResponseEntity.ok(
                ApiResponse.success(
                    templates.map { it.toResponse() },
                    "All templates retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error retrieving all templates", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Create new template
     */
    @PostMapping
    fun createTemplate(
        @Valid @RequestBody request: DocumentTemplateRequest
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("POST /api/v1/document-templates - Creating template: {}", request.templateName)

        return try {
            val template = request.toModel()
            val createdTemplate = documentTemplateService.createTemplate(template)

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(createdTemplate.toResponse(), "Template created successfully")
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Validation error creating template: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.error("Validation error: ${e.message}")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error creating template: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error(e.message ?: "Conflict error")
            )
        } catch (e: Exception) {
            logger.error("Error creating template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Update existing template
     */
    @PutMapping("/{templateId}")
    fun updateTemplate(
        @PathVariable templateId: String,
        @Valid @RequestBody request: DocumentTemplateRequest
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("PUT /api/v1/document-templates/{}", templateId)

        return try {
            val updatedTemplate = documentTemplateService.updateTemplate(templateId, request.toModel())

            ResponseEntity.ok(
                ApiResponse.success(updatedTemplate.toResponse(), "Template updated successfully")
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Validation error updating template: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.error("Validation error: ${e.message}")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error updating template: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error(e.message ?: "Conflict error")
            )
        } catch (e: Exception) {
            logger.error("Error updating template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Activate a template
     */
    @PostMapping("/{templateId}/activate")
    fun activateTemplate(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("POST /api/v1/document-templates/{}/activate", templateId)

        return try {
            val activatedTemplate = documentTemplateService.activateTemplate(templateId)

            ResponseEntity.ok(
                ApiResponse.success(activatedTemplate.toResponse(), "Template activated successfully")
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Error activating template: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.error(e.message ?: "Template not found")
            )
        } catch (e: Exception) {
            logger.error("Error activating template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Deactivate a template
     */
    @PostMapping("/{templateId}/deactivate")
    fun deactivateTemplate(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<DocumentTemplateResponse>> {
        logger.info("POST /api/v1/document-templates/{}/deactivate", templateId)

        return try {
            val deactivatedTemplate = documentTemplateService.deactivateTemplate(templateId)

            ResponseEntity.ok(
                ApiResponse.success(deactivatedTemplate.toResponse(), "Template deactivated successfully")
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Error deactivating template: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.error(e.message ?: "Template not found")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error deactivating template: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error(e.message ?: "Cannot deactivate default template")
            )
        } catch (e: Exception) {
            logger.error("Error deactivating template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Delete a template
     */
    @DeleteMapping("/{templateId}")
    fun deleteTemplate(
        @PathVariable templateId: String
    ): ResponseEntity<ApiResponse<String>> {
        logger.info("DELETE /api/v1/document-templates/{}", templateId)

        return try {
            documentTemplateService.deleteTemplate(templateId)

            ResponseEntity.ok(
                ApiResponse.success("Template deleted successfully", "Template deleted successfully")
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Error deleting template: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.error(e.message ?: "Template not found")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error deleting template: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error(e.message ?: "Cannot delete default template")
            )
        } catch (e: Exception) {
            logger.error("Error deleting template", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    // Extension functions for mapping
    private fun DocumentTemplate.toResponse() = DocumentTemplateResponse(
        templateId = templateId,
        documentType = documentType,
        templateName = templateName,
        templateVersion = templateVersion,
        htmlTemplate = htmlTemplate,
        cssContent = cssContent,
        commonConfig = commonConfig,
        documentConfig = documentConfig,
        isActive = isActive,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun DocumentTemplateRequest.toModel() = DocumentTemplate(
        templateId = null,
        documentType = documentType,
        templateName = templateName,
        templateVersion = templateVersion,
        htmlTemplate = htmlTemplate,
        cssContent = cssContent,
        commonConfig = commonConfig,
        documentConfig = documentConfig,
        isActive = isActive,
        isDefault = isDefault
    )
}
