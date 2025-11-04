package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.TaskManagementConfigRequest
import com.wmspro.tenant.dto.TaskManagementConfigResponse
import com.wmspro.tenant.service.TaskManagementConfigService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Controller for Task Management Configuration operations
 */
@RestController
@RequestMapping("/api/v1/task-management-config")
@Tag(name = "Task Management Configuration", description = "APIs for managing task configuration and SLA settings")
class TaskManagementConfigController(
    private val taskManagementConfigService: TaskManagementConfigService
) {
    private val logger = LoggerFactory.getLogger(TaskManagementConfigController::class.java)

    /**
     * Get task management configuration
     */
    @GetMapping
    @Operation(
        summary = "Get Task Management Configuration",
        description = "Retrieve task management configuration including SLA settings for the tenant"
    )
    fun getTaskManagementConfig(): ResponseEntity<ApiResponse<TaskManagementConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.debug("Fetching task management config for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = taskManagementConfigService.getTaskManagementConfig(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Task management configuration retrieved successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Tenant not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Tenant not found")
            )
        } catch (e: Exception) {
            logger.error("Error fetching task management configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to retrieve task management configuration")
            )
        }
    }

    /**
     * Update task management configuration
     */
    @PutMapping
    @Operation(
        summary = "Update Task Management Configuration",
        description = "Update task management configuration including SLA settings"
    )
    fun updateTaskManagementConfig(
        @Valid @RequestBody request: TaskManagementConfigRequest
    ): ResponseEntity<ApiResponse<TaskManagementConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Updating task management config for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = taskManagementConfigService.updateTaskManagementConfig(tenantId.toInt(), request)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Task management configuration updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid task management config update: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(e.message ?: "Invalid request")
            )
        } catch (e: Exception) {
            logger.error("Error updating task management configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to update task management configuration")
            )
        }
    }

    /**
     * Reset task management configuration to defaults
     */
    @PostMapping("/reset-defaults")
    @Operation(
        summary = "Reset to Default Configuration",
        description = "Reset task management configuration to default values"
    )
    fun resetToDefaults(): ResponseEntity<ApiResponse<TaskManagementConfigResponse>> {
        val tenantId = TenantContext.getCurrentTenant()
        logger.info("Resetting task management config to defaults for tenant: $tenantId")

        return try {
            if (tenantId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error("Tenant context missing or invalid")
                )
            }

            val response = taskManagementConfigService.resetToDefaults(tenantId.toInt())

            ResponseEntity.ok(
                ApiResponse.success(
                    data = response,
                    message = "Task management configuration reset to defaults successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Tenant not found: ${e.message}")
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error(e.message ?: "Tenant not found")
            )
        } catch (e: Exception) {
            logger.error("Error resetting task management configuration", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Failed to reset task management configuration")
            )
        }
    }
}
