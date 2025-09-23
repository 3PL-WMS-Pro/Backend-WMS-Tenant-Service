package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.interceptor.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.TenantSettings
import com.wmspro.tenant.service.TenantSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

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

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<TenantSettingsResponse>(
                        message = "Tenant context missing or invalid"
                    )
                )
            }

            val settings = tenantSettingsService.getTenantSettings(tenantId.toInt())
            if (settings != null) {
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
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
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
}

/**
 * Custom exception for not found scenarios
 */
class NotFoundException(message: String) : RuntimeException(message)