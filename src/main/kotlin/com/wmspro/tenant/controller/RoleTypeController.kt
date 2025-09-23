package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.RoleType
import com.wmspro.tenant.service.RoleTypeService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

/**
 * Controller for role type operations
 * Manages role templates with permissions in tenant-specific databases
 */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Role Management", description = "APIs for managing role types and permissions")
class RoleTypeController(
    private val roleTypeService: RoleTypeService
) {
    private val logger = LoggerFactory.getLogger(RoleTypeController::class.java)

    @PostMapping
    @Operation(summary = "Create role type", description = "Creates a new role type with permissions")
    fun createRole(
        @Valid @RequestBody request: CreateRoleRequest
    ): ResponseEntity<ApiResponse<RoleType>> {
        logger.info("Creating new role type: ${request.roleName}")

        return try {
            val roleCode = roleTypeService.generateNextRoleCode()
            val createdRole = roleTypeService.createRole(request.toRoleType(roleCode))
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = createdRole,
                    message = "Role type created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid role creation request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<RoleType>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error creating role type", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<RoleType>(
                    message = "Failed to create role type"
                )
            )
        }
    }

    @GetMapping("/{roleCode}")
    @Operation(summary = "Get role by code", description = "Retrieves role type by role code")
    fun getRole(
        @PathVariable roleCode: String
    ): ResponseEntity<ApiResponse<RoleType>> {
        logger.debug("Fetching role type: $roleCode")

        val role = roleTypeService.getRoleByCode(roleCode)
        return if (role != null) {
            ResponseEntity.ok(
                ApiResponse.success(
                    data = role,
                    message = "Role type retrieved successfully"
                )
            )
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error<RoleType>(
                    message = "Role type not found: $roleCode"
                )
            )
        }
    }

    @PutMapping("/{roleCode}")
    @Operation(summary = "Update role type", description = "Updates role type permissions and configuration")
    fun updateRole(
        @PathVariable roleCode: String,
        @Valid @RequestBody request: UpdateRoleTypeRequest
    ): ResponseEntity<ApiResponse<RoleType>> {
        logger.info("Updating role type: $roleCode")

        return try {
            val updates = mutableMapOf<String, Any>()
            request.displayName?.let { updates["displayName"] = it }
            request.description?.let { updates["description"] = it }
            request.hierarchyLevel?.let { updates["hierarchyLevel"] = it }
            request.defaultPermissions?.let { updates["defaultPermissions"] = it }
            request.menuAccess?.let { updates["menuAccess"] = it }
            request.apiAccessPatterns?.let { updates["apiAccessPatterns"] = it }
            request.mobileFeatures?.let { updates["mobileFeatures"] = it }

            val updatedRole = roleTypeService.updateRole(roleCode, updates)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = updatedRole,
                    message = "Role type updated successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid role update request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<RoleType>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating role type", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<RoleType>(
                    message = "Failed to update role type"
                )
            )
        }
    }

    @DeleteMapping("/{roleCode}")
    @Operation(summary = "Delete role type", description = "Deletes a role type")
    fun deleteRole(
        @PathVariable roleCode: String
    ): ResponseEntity<ApiResponse<Unit>> {
        logger.info("Deleting role type: $roleCode")

        return try {
            roleTypeService.deleteRole(roleCode)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = Unit,
                    message = "Role type deleted successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid role deletion request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<Unit>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error deleting role type", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<Unit>(
                    message = "Failed to delete role type"
                )
            )
        }
    }

    @GetMapping
    @Operation(summary = "List all roles", description = "Retrieves all role types for current tenant")
    fun getAllRoles(
        @RequestParam(required = false) systemOnly: Boolean = false
    ): ResponseEntity<ApiResponse<RoleTypeListResponse>> {
        logger.debug("Fetching all role types, systemOnly: $systemOnly")

        val roles = if (systemOnly) {
            roleTypeService.getSystemRoles()
        } else {
            roleTypeService.getAllRoles()
        }

        val response = RoleTypeListResponse(
            roles = roles.map { it.toSummary() },
            totalCount = roles.size
        )

        return ResponseEntity.ok(
            ApiResponse.success(
                data = response,
                message = "Role types retrieved successfully"
            )
        )
    }

    @GetMapping("/hierarchy/{maxLevel}")
    @Operation(summary = "Get roles by hierarchy", description = "Retrieves roles up to specified hierarchy level")
    fun getRolesByHierarchy(
        @PathVariable maxLevel: Int
    ): ResponseEntity<ApiResponse<RoleTypeListResponse>> {
        logger.debug("Fetching roles with hierarchy level <= $maxLevel")

        val roles = roleTypeService.getRolesByHierarchy(maxLevel)
        val response = RoleTypeListResponse(
            roles = roles.map { it.toSummary() },
            totalCount = roles.size
        )

        return ResponseEntity.ok(
            ApiResponse.success(
                data = response,
                message = "Roles retrieved successfully"
            )
        )
    }

    @PostMapping("/initialize-system-roles")
    @Operation(summary = "Initialize system roles", description = "Creates default system roles for new tenant")
    fun initializeSystemRoles(): ResponseEntity<ApiResponse<String>> {
        logger.info("Initializing system roles")

        return try {
            roleTypeService.initializeSystemRoles()
            ResponseEntity.ok(
                ApiResponse.success(
                    data = "System roles initialized",
                    message = "System roles initialized successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error initializing system roles", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<String>(
                    message = "Failed to initialize system roles"
                )
            )
        }
    }

    @GetMapping("/search/permission")
    @Operation(summary = "Find roles by permission", description = "Finds roles with specific permission")
    fun findRolesByPermission(
        @RequestParam permissionName: String,
        @RequestParam value: Boolean
    ): ResponseEntity<ApiResponse<RoleTypeListResponse>> {
        logger.debug("Finding roles with permission $permissionName=$value")

        val roles = roleTypeService.findRolesWithPermission(permissionName, value)
        val response = RoleTypeListResponse(
            roles = roles.map { it.toSummary() },
            totalCount = roles.size
        )

        return ResponseEntity.ok(
            ApiResponse.success(
                data = response,
                message = "Roles retrieved successfully"
            )
        )
    }
}