package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.service.TenantService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

/**
 * Controller for tenant database mapping operations
 * Manages central database operations for multi-tenant configuration
 * Implements APIs 066, 070, 073 from API Specsheet
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Tenant Management", description = "APIs for managing tenant database mappings")
class TenantController(
    private val tenantService: TenantService
) {
    private val logger = LoggerFactory.getLogger(TenantController::class.java)

    /**
     * API 066: Create New Tenant
     * Creates a new tenant with database configuration and initializes their isolated environment
     */
    @PostMapping("/tenants")
    @Operation(
        summary = "Create New Tenant",
        description = "Creates a new tenant with database configuration and initializes their isolated environment"
    )
    fun createTenant(
        @Valid @RequestBody request: CreateTenantRequest
    ): ResponseEntity<ApiResponse<CreateTenantResponse>> {
        logger.info("Creating new tenant with client ID: ${request.clientId}")

        return try {
            // Validate connections and create tenant
            val tenant = tenantService.createTenantWithValidation(request)

            // Return secure response without sensitive data
            val response = CreateTenantResponse(
                clientId = tenant.clientId,
                databaseName = tenant.mongoConnection.databaseName,
                s3Configuration = SecureS3Config(
                    bucketName = tenant.s3Configuration.bucketName,
                    region = tenant.s3Configuration.region ?: "ap-south-1",
                    bucketPrefix = tenant.s3Configuration.bucketPrefix
                ),
                tenantSettings = tenant.tenantSettings,
                status = tenant.status.name,
                createdAt = tenant.createdAt,
                updatedAt = tenant.updatedAt
            )

            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = response,
                    message = "Tenant created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid tenant creation request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<CreateTenantResponse>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: DuplicateKeyException) {
            logger.error("Duplicate tenant: ${e.message}")
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error<CreateTenantResponse>(
                    message = "Duplicate client_id or database_name"
                )
            )
        } catch (e: ConnectionTestException) {
            logger.error("Connection test failed: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<CreateTenantResponse>(
                    message = "Connection test failed: ${e.message}"
                )
            )
        } catch (e: Exception) {
            logger.error("Error creating tenant", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<CreateTenantResponse>(
                    message = "Failed to create tenant"
                )
            )
        }
    }

    /**
     * API 070: Get Tenant by Client ID
     * Retrieves tenant information by client ID for administrative purposes
     */
    @GetMapping("/tenants/client/{clientId}")
    @Operation(
        summary = "Get Tenant by Client ID",
        description = "Retrieves tenant information by client ID for administrative purposes"
    )
    fun getTenantByClientId(
        @PathVariable clientId: Int,
        @RequestParam(required = false, defaultValue = "false") includeSettings: Boolean
    ): ResponseEntity<ApiResponse<TenantInfoResponse>> {
        logger.debug("Fetching tenant with client ID: $clientId, includeSettings: $includeSettings")

        val tenant = tenantService.getTenantByClientId(clientId)
        return if (tenant != null) {
            val healthStatus = tenantService.checkConnectionHealth(clientId)

            val response = TenantInfoResponse(
                clientId = tenant.clientId,
                status = tenant.status.name,
                databaseName = tenant.mongoConnection.databaseName,
                s3Configuration = SecureS3Config(
                    bucketName = tenant.s3Configuration.bucketName,
                    region = tenant.s3Configuration.region ?: "ap-south-1",
                    bucketPrefix = null // Don't expose prefix in this endpoint
                ),
                tenantSettings = if (includeSettings) tenant.tenantSettings else null,
                connectionHealth = healthStatus,
                lastConnected = tenant.lastConnected,
                createdAt = tenant.createdAt,
                updatedAt = tenant.updatedAt
            )

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Tenant retrieved successfully"
                )
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<TenantInfoResponse>(
                    message = "Tenant not found with client ID: $clientId"
                )
            )
        }
    }

    /**
     * API 073: Get Tenant by ID
     * Retrieves tenant information by internal tenant ID (MongoDB ObjectId)
     */
    @GetMapping("/tenants/{tenantId}")
    @Operation(
        summary = "Get Tenant by ID",
        description = "Retrieves tenant information by internal tenant ID (MongoDB ObjectId)"
    )
    fun getTenantById(
        @PathVariable tenantId: String,
        @RequestParam(required = false, defaultValue = "false") includeStats: Boolean
    ): ResponseEntity<ApiResponse<TenantDetailsResponse>> {
        logger.debug("Fetching tenant with ID: $tenantId, includeStats: $includeStats")

        return try {
            // Validate ObjectId format
            if (!ObjectId.isValid(tenantId)) {
                return ResponseEntity.badRequest().body(
                    ApiResponse.error<TenantDetailsResponse>(
                        message = "Invalid ObjectId format: $tenantId"
                    )
                )
            }

            val tenant = tenantService.getTenantById(tenantId)
            if (tenant != null) {
                val healthStatus = tenantService.checkConnectionHealth(tenant.clientId)

                val response = TenantDetailsResponse(
                    id = tenant.clientId.toString(),
                    clientId = tenant.clientId,
                    status = tenant.status.name,
                    databaseName = tenant.mongoConnection.databaseName,
                    s3Configuration = SecureS3Config(
                        bucketName = tenant.s3Configuration.bucketName,
                        region = tenant.s3Configuration.region ?: "ap-south-1",
                        bucketPrefix = null
                    ),
                    tenantSettingsSummary = TenantSettingsSummary(
                        autoAssignmentStrategy = tenant.tenantSettings.taskConfigurations.autoAssignment.strategy.name,
                        slaSettingsConfigured = tenant.tenantSettings.taskConfigurations.slaSettings != null
                    ),
                    usageStats = if (includeStats) {
                        tenantService.calculateUsageStats(tenant.clientId)
                    } else null,
                    connectionHealth = healthStatus,
                    createdAt = tenant.createdAt,
                    updatedAt = tenant.updatedAt
                )

                ResponseEntity.ok(
                    ApiResponse.success(
                        data = response,
                        message = "Tenant retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<TenantDetailsResponse>(
                        message = "Tenant not found with ID: $tenantId"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching tenant by ID", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<TenantDetailsResponse>(
                    message = "Failed to retrieve tenant"
                )
            )
        }
    }

    // Remove all other methods - they don't belong in this controller
    @PutMapping("/{clientId}")
    @Deprecated(message = "This endpoint should not exist in TenantController")
    @Operation(summary = "Update tenant", description = "Updates tenant database mapping - REMOVED")
    fun updateTenant(
        @PathVariable clientId: Int,
        @Valid @RequestBody request: UpdateTenantRequest
    ): ResponseEntity<ApiResponse<Any>> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiResponse.error<Any>(
                message = "This endpoint has been removed per API specification"
            )
        )
    }
}