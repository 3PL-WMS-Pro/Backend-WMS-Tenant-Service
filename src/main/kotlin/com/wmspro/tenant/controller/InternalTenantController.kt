package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.MongoConnectionConfig
import com.wmspro.tenant.model.S3Configuration
import com.wmspro.tenant.model.TenantSettings
import com.wmspro.tenant.service.InternalTenantService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Internal controller for service-to-service communication
 * These endpoints are for internal microservice communication only
 * Should NOT be exposed to external API gateway
 * Implements APIs 069, 071, 072 from API Specsheet
 *
 * Security Note: These endpoints rely on network-level security
 * (service mesh, internal network isolation) rather than token authentication
 */
@RestController
@RequestMapping("/api/v1/tenants/internal")
@Tag(name = "Internal Tenant APIs", description = "Service-to-service APIs for tenant configuration")
class InternalTenantController(
    private val internalTenantService: InternalTenantService
) {
    private val logger = LoggerFactory.getLogger(InternalTenantController::class.java)

    /**
     * API 069: Get Database Connection (Internal Service-to-Service)
     * Returns MongoDB connection for other microservices
     */
    @GetMapping("/{clientId}/database-connection")
    @Operation(
        summary = "Get Database Connection",
        description = "Internal endpoint for other microservices to retrieve tenant database configuration"
    )
    fun getDatabaseConnection(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<MongoConnectionResponse>> {
        logger.info("Service requesting database connection for client ID: $clientId")

        return try {

            val connection = internalTenantService.getDatabaseConnection(clientId)
            if (connection != null) {
                // Log service access for auditing
                logger.info("Service accessed database connection for client $clientId")

                ResponseEntity.ok(
                    ApiResponse.success(
                        data = connection,
                        message = "Database connection retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<MongoConnectionResponse>(
                        message = "Tenant not found with client ID: $clientId"
                    )
                )
            }
        } catch (e: InactiveTenantException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error<MongoConnectionResponse>(
                    message = "Tenant is inactive"
                )
            )
        } catch (e: Exception) {
            logger.error("Error retrieving database connection", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<MongoConnectionResponse>(
                    message = "Failed to retrieve database connection"
                )
            )
        }
    }

    /**
     * API 071: Get S3 Configuration (Internal Service-to-Service)
     * Returns S3 configuration for File Service
     */
    @GetMapping("/{clientId}/s3-configuration")
    @Operation(
        summary = "Get S3 Configuration",
        description = "Internal endpoint for File Service to retrieve tenant S3 configuration"
    )
    fun getS3Configuration(
        @PathVariable clientId: Int
    ): ResponseEntity<ApiResponse<S3Configuration>> {
        logger.info("Service requesting S3 configuration for client ID: $clientId")

        return try {

            val s3Config = internalTenantService.getS3Configuration(clientId)
            if (s3Config != null) {
                // Log access for auditing
                logger.info("Service accessed S3 configuration for client $clientId")

                ResponseEntity.ok(
                    ApiResponse.success(
                        data = s3Config,
                        message = "S3 configuration retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<S3Configuration>(
                        message = "Tenant not found with client ID: $clientId"
                    )
                )
            }
        } catch (e: InactiveTenantException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error<S3Configuration>(
                    message = "Tenant is inactive"
                )
            )
        } catch (e: Exception) {
            logger.error("Error retrieving S3 configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<S3Configuration>(
                    message = "Failed to retrieve S3 configuration"
                )
            )
        }
    }

    /**
     * API 072: Get Tenant Settings by Client ID (Internal Service-to-Service)
     * Returns tenant settings for processing logic in other services
     */
    @GetMapping("/{clientId}/settings")
    @Operation(
        summary = "Get Tenant Settings by Client ID",
        description = "Internal endpoint for other services to retrieve tenant settings for processing logic"
    )
    fun getTenantSettingsByClientId(
        @PathVariable clientId: Int,
        @RequestParam(required = false) settingsPath: String?
    ): ResponseEntity<ApiResponse<Any>> {
        logger.info("Service requesting tenant settings for client ID: $clientId, path: $settingsPath")

        return try {

            val settings = if (settingsPath != null) {
                internalTenantService.getTenantSettingsByPath(clientId, settingsPath)
            } else {
                internalTenantService.getAllTenantSettings(clientId)
            }

            if (settings != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = settings,
                        message = "Tenant settings retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<Any>(
                        message = if (settingsPath != null) {
                            "Settings path not found: $settingsPath"
                        } else {
                            "Tenant not found with client ID: $clientId"
                        }
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error retrieving tenant settings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<Any>(
                    message = "Failed to retrieve tenant settings"
                )
            )
        }
    }
}

/**
 * Response DTO for MongoDB connection (API 069)
 */
data class MongoConnectionResponse(
    val url: String,
    val databaseName: String,
    val connectionOptions: Map<String, Any>
)

/**
 * Custom exception for inactive tenant
 */
class InactiveTenantException(message: String) : RuntimeException(message)