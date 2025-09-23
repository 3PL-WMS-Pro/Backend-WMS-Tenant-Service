package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.TenantDatabaseMapping
import com.wmspro.tenant.model.TenantStatus
import com.wmspro.tenant.service.TenantService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

/**
 * Controller for tenant database mapping operations
 * Manages central database operations for multi-tenant configuration
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Tenant Management", description = "APIs for managing tenant database mappings")
class TenantController(
    private val tenantService: TenantService
) {
    private val logger = LoggerFactory.getLogger(TenantController::class.java)

    @PostMapping
    @Operation(summary = "Create new tenant", description = "Creates a new tenant database mapping")
    fun createTenant(
        @Valid @RequestBody tenant: TenantDatabaseMapping
    ): ResponseEntity<ApiResponse<SecureTenantResponse>> {
        logger.info("Creating new tenant with client ID: ${tenant.clientId}")

        return try {
            val createdTenant = tenantService.createTenant(tenant)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = createdTenant.toSecureResponse(),
                    message = "Tenant created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid tenant creation request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<SecureTenantResponse>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error creating tenant", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<SecureTenantResponse>(
                    message = "Failed to create tenant"
                )
            )
        }
    }

    @GetMapping("/{clientId}")
    @Operation(summary = "Get tenant by client ID", description = "Retrieves tenant database mapping by client ID")
    fun getTenant(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<TenantDatabaseMapping>> {
        logger.debug("Fetching tenant with client ID: $clientId")

        val tenant = tenantService.getTenantByClientId(clientId)
        return if (tenant != null) {
            ResponseEntity.ok(
                ApiResponse.success(
                    data = tenant,
                    message = "Tenant retrieved successfully"
                )
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<TenantDatabaseMapping>(
                    message = "Tenant not found with client ID: $clientId"
                )
            )
        }
    }

    @PutMapping("/{clientId}")
    @Operation(summary = "Update tenant", description = "Updates tenant database mapping")
    fun updateTenant(
        @PathVariable clientId: Int,
        @Valid @RequestBody request: UpdateTenantRequest
    ): ResponseEntity<ApiResponse<SecureTenantResponse>> {
        logger.info("Updating tenant with client ID: $clientId")

        return try {
            val updates = mutableMapOf<String, Any>()
            request.status?.let { updates["status"] = it }
            request.mongoConnection?.let { updates["mongoConnection"] = it }
            request.s3Configuration?.let { updates["s3Configuration"] = it }
            request.tenantSettings?.let { updates["tenantSettings"] = it }

            val tenant = tenantService.updateTenant(clientId, updates)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = tenant.toSecureResponse(),
                    message = "Tenant updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid tenant update request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<SecureTenantResponse>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating tenant", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<SecureTenantResponse>(
                    message = "Failed to update tenant"
                )
            )
        }
    }

    @DeleteMapping("/{clientId}")
    @Operation(summary = "Delete tenant", description = "Deletes tenant database mapping")
    fun deleteTenant(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<Unit>> {
        logger.info("Deleting tenant with client ID: $clientId")

        return try {
            tenantService.deleteTenant(clientId)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "Tenant deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid tenant deletion request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<Unit>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error deleting tenant", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<Unit>(
                    message = "Failed to delete tenant"
                )
            )
        }
    }

    @GetMapping
    @Operation(summary = "List all tenants", description = "Retrieves all tenant database mappings")
    fun getAllTenants(
        @RequestParam(required = false) status: String?
    ): ResponseEntity<ApiResponse<List<TenantSummary>>> {
        logger.debug("Fetching all tenants with status filter: $status")

        val tenants = if (status != null) {
            tenantService.getActiveTenants()
        } else {
            tenantService.getAllTenants()
        }

        val summaries = tenants.map { it.toSummary() }

        return ResponseEntity.ok(
            ApiResponse.success(
                data = summaries,
                message = "Tenants retrieved successfully"
            )
        )
    }

    @PostMapping("/{clientId}/validate-connection")
    @Operation(summary = "Validate tenant connection", description = "Validates MongoDB connection for a tenant")
    fun validateConnection(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        logger.info("Validating connection for tenant: $clientId")

        return try {
            val tenant = tenantService.getTenantByClientId(clientId)
            if (tenant != null) {
                tenantService.updateConnectionStatus(clientId, true)
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = mapOf(
                            "clientId" to clientId,
                            "connectionValid" to true,
                            "databaseName" to tenant.mongoConnection.databaseName
                        ),
                        message = "Connection validated successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<Map<String, Any>>(
                        message = "Tenant not found"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error validating connection", e)
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                ApiResponse.error<Map<String, Any>>(
                    message = "Connection validation failed: ${e.message}"
                )
            )
        }
    }

    @GetMapping("/check/{clientId}")
    @Operation(summary = "Check tenant exists", description = "Checks if a tenant exists by client ID")
    fun checkTenantExists(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<Boolean>> {
        val exists = tenantService.tenantExists(clientId)
        return ResponseEntity.ok(
            ApiResponse.success(
                data = exists,
                message = if (exists) "Tenant exists" else "Tenant does not exist"
            )
        )
    }
}