package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.service.ECommercePlatformConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for managing e-commerce platform configurations
 */
@RestController
@RequestMapping("/api/v1/ecommerce-platform-config")
@Tag(name = "E-Commerce Platform Configuration", description = "Manage e-commerce platform integrations")
class ECommercePlatformConfigController(
    private val ecommercePlatformConfigService: ECommercePlatformConfigService
) {
    private val logger = LoggerFactory.getLogger(ECommercePlatformConfigController::class.java)

    /**
     * Create a new e-commerce platform configuration
     */
    @PostMapping
    @Operation(
        summary = "Create e-commerce platform configuration",
        description = "Creates a new e-commerce platform configuration for the tenant"
    )
    fun createECommercePlatformConfig(
        @Valid @RequestBody request: CreateECommercePlatformConfigRequest
    ): ResponseEntity<ApiResponse<ECommercePlatformConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Creating e-commerce platform configuration for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = ecommercePlatformConfigService.createECommercePlatformConfig(tenantId.toInt(), request)

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = response,
                    message = "E-commerce platform configuration created successfully"
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
            logger.error("Error creating e-commerce platform configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to create e-commerce platform configuration")
            )
        }
    }

    /**
     * Get all e-commerce platform configurations for the tenant
     */
    @GetMapping
    @Operation(
        summary = "Get all e-commerce platform configurations",
        description = "Retrieves all e-commerce platform configurations for the tenant"
    )
    fun getAllECommercePlatformConfigs(): ResponseEntity<ApiResponse<ECommercePlatformConfigListResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching all e-commerce platform configurations for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = ecommercePlatformConfigService.getAllECommercePlatformConfigs(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "E-commerce platform configurations retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching e-commerce platform configurations", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to fetch e-commerce platform configurations")
            )
        }
    }

    /**
     * Get e-commerce platform configuration by ID
     */
    @GetMapping("/{id}")
    @Operation(
        summary = "Get e-commerce platform configuration by ID",
        description = "Retrieves a specific e-commerce platform configuration by ID"
    )
    fun getECommercePlatformConfigById(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<ECommercePlatformConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching e-commerce platform configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = ecommercePlatformConfigService.getECommercePlatformConfigById(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "E-commerce platform configuration retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("E-commerce platform configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "E-commerce platform configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error fetching e-commerce platform configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to fetch e-commerce platform configuration")
            )
        }
    }

    /**
     * Update e-commerce platform configuration
     */
    @PutMapping("/{id}")
    @Operation(
        summary = "Update e-commerce platform configuration",
        description = "Updates an existing e-commerce platform configuration"
    )
    fun updateECommercePlatformConfig(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateECommercePlatformConfigRequest
    ): ResponseEntity<ApiResponse<ECommercePlatformConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating e-commerce platform configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = ecommercePlatformConfigService.updateECommercePlatformConfig(tenantId.toInt(), id, request)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "E-commerce platform configuration updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid request or e-commerce platform configuration not found: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: IllegalStateException) {
            logger.error("State error: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(e.message ?: "Failed to process request")
            )
        } catch (e: Exception) {
            logger.error("Error updating e-commerce platform configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to update e-commerce platform configuration")
            )
        }
    }

    /**
     * Delete e-commerce platform configuration
     */
    @DeleteMapping("/{id}")
    @Operation(
        summary = "Delete e-commerce platform configuration",
        description = "Deletes an e-commerce platform configuration"
    )
    fun deleteECommercePlatformConfig(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<Unit>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Deleting e-commerce platform configuration: $id for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            ecommercePlatformConfigService.deleteECommercePlatformConfig(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "E-commerce platform configuration deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("E-commerce platform configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "E-commerce platform configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error deleting e-commerce platform configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to delete e-commerce platform configuration")
            )
        }
    }

    /**
     * Test e-commerce platform connection
     */
    @PostMapping("/{id}/test-connection")
    @Operation(
        summary = "Test e-commerce platform connection",
        description = "Tests the e-commerce platform connection with the configured credentials"
    )
    fun testECommercePlatformConnection(
        @PathVariable id: String
    ): ResponseEntity<ApiResponse<ConnectionTestResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Testing e-commerce platform connection for config: $id, tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = ecommercePlatformConfigService.testECommercePlatformConnection(tenantId.toInt(), id)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = if (response.success) "Connection test completed successfully" else "Connection test failed"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("E-commerce platform configuration not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "E-commerce platform configuration not found")
            )
        } catch (e: Exception) {
            logger.error("Error testing e-commerce platform connection", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to test e-commerce platform connection")
            )
        }
    }
}
