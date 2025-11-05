package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.service.S3StorageConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for managing S3 storage configurations
 */
@RestController
@RequestMapping("/api/v1/s3-storage-config")
@Tag(name = "S3 Storage Configuration", description = "Manage S3 storage configurations for document storage")
class S3StorageConfigController(
    private val s3StorageConfigService: S3StorageConfigService
) {
    private val logger = LoggerFactory.getLogger(S3StorageConfigController::class.java)

    /**
     * Create a new S3 storage configuration
     */
    @PostMapping
    @Operation(
        summary = "Create S3 storage configuration",
        description = "Creates a new S3 storage configuration for the tenant"
    )
    fun createS3StorageConfig(
        @Valid @RequestBody request: CreateS3StorageConfigRequest
    ): ResponseEntity<ApiResponse<S3StorageConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Creating S3 storage configuration for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = s3StorageConfigService.createS3StorageConfig(tenantId.toInt(), request)

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = response,
                    message = "S3 storage configuration created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid request: ${e.message}")
            ResponseEntity.badRequest().body(ApiResponse.error(e.message ?: "Invalid request"))
        } catch (e: IllegalStateException) {
            logger.error("State error: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(e.message ?: "Failed to process request")
            )
        } catch (e: Exception) {
            logger.error("Error creating S3 storage configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to create S3 storage configuration")
            )
        }
    }

    /**
     * Get all S3 storage configurations for the tenant
     */
    @GetMapping
    @Operation(
        summary = "Get all S3 storage configurations",
        description = "Retrieves all S3 storage configurations for the tenant"
    )
    fun getAllS3StorageConfigs(): ResponseEntity<ApiResponse<S3StorageConfigListResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching all S3 storage configurations for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = s3StorageConfigService.getAllS3StorageConfigs(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "S3 storage configurations retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching S3 storage configurations", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to fetch S3 storage configurations")
            )
        }
    }

    /**
     * Get S3 storage configuration by ID
     */
    @GetMapping("/{id}")
    @Operation(
        summary = "Get S3 storage configuration by ID",
        description = "Retrieves a specific S3 storage configuration by ID"
    )
    fun getS3StorageConfigById(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<S3StorageConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching S3 storage configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = s3StorageConfigService.getS3StorageConfigById(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "S3 storage configuration retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("S3 storage configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "S3 storage configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error fetching S3 storage configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to fetch S3 storage configuration")
            )
        }
    }

    /**
     * Update S3 storage configuration
     */
    @PutMapping("/{id}")
    @Operation(
        summary = "Update S3 storage configuration",
        description = "Updates an existing S3 storage configuration"
    )
    fun updateS3StorageConfig(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateS3StorageConfigRequest
    ): ResponseEntity<ApiResponse<S3StorageConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating S3 storage configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = s3StorageConfigService.updateS3StorageConfig(tenantId.toInt(), id, request)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "S3 storage configuration updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid request or S3 storage configuration not found: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(e.message ?: "Failed to process request")
            )
        } catch (e: Exception) {
            logger.error("Error updating S3 storage configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to update S3 storage configuration")
            )
        }
    }

    /**
     * Delete S3 storage configuration
     */
    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete S3 storage configuration",
        description = "Deletes an S3 storage configuration"
    )
    fun deleteS3StorageConfig(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<Unit>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Deleting S3 storage configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            s3StorageConfigService.deleteS3StorageConfig(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "S3 storage configuration deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("S3 storage configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "S3 storage configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error deleting S3 storage configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to delete S3 storage configuration")
            )
        }
    }

    /**
     * Test S3 connection
     */
    @PostMapping("/{id}/test-connection")
    @Operation(
        summary = "Test S3 connection",
        description = "Tests the S3 connection with the configured credentials"
    )
    fun testS3Connection(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<ConnectionTestResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Testing S3 connection for config: $id, tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = s3StorageConfigService.testS3Connection(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = if (response.success) "Connection test completed successfully" else "Connection test failed"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("S3 storage configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "S3 storage configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error testing S3 connection", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to test S3 connection")
            )
        }
    }
}
