package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.service.UserRoleMappingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import jakarta.validation.Valid

/**
 * Controller for user role mapping operations
 * Manages user-role assignments in tenant-specific databases
 * Implements APIs 074-080 from API Specsheet
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "User Role Management", description = "APIs for managing user roles and permissions")
class UserRoleMappingController(
    private val userRoleMappingService: UserRoleMappingService
) {
    private val logger = LoggerFactory.getLogger(UserRoleMappingController::class.java)

    /**
     * API 074: Create User Role Mapping
     * Creates a new user role mapping, assigning permissions and warehouse access
     */
    @PostMapping("/user-role-mappings")
    @Operation(
        summary = "Create User Role Mapping",
        description = "Creates a new user role mapping, assigning permissions and warehouse access to a user within a tenant"
    )
    fun createUserRoleMapping(
        @Valid @RequestBody mapping: UserRoleMapping
    ): ResponseEntity<ApiResponse<UserRoleMapping>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.info("Creating user role mapping for email: ${mapping.email}, client: $clientId")

        return try {
            // Validate client_id from auth context
            if (clientId == null || clientId != mapping.clientId) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<UserRoleMapping>(
                        message = "Client ID mismatch or missing tenant context"
                    )
                )
            }

            val created = userRoleMappingService.createUserRoleMapping(mapping)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = created,
                    message = "User role mapping created successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid user role mapping: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<UserRoleMapping>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: DuplicateUserException) {
            logger.error("Duplicate user: ${e.message}")
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error<UserRoleMapping>(
                    message = "User role mapping already exists for this email + client_id combination"
                )
            )
        } catch (e: Exception) {
            logger.error("Error creating user role mapping", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<UserRoleMapping>(
                    message = "Failed to create user role mapping"
                )
            )
        }
    }

    /**
     * API 075: Get User Role Mappings List
     * Retrieves paginated list of user role mappings for the current tenant
     */
    @GetMapping("/user-role-mappings")
    @Operation(
        summary = "Get User Role Mappings List",
        description = "Retrieves paginated list of user role mappings for the current tenant with filtering options"
    )
    fun getUserRoleMappingsList(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) roleType: String?,
        @RequestParam(required = false) warehouse: String?,
        @RequestParam(required = false) isActive: Boolean?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) hasPermission: String?,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortOrder: String
    ): ResponseEntity<ApiResponse<UserRoleMappingListResponse>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.debug("Fetching user role mappings for client: $clientId")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<UserRoleMappingListResponse>(
                        message = "Missing tenant context"
                    )
                )
            }

            val filters = UserRoleMappingFilters(
                clientId = clientId,
                roleType = roleType,
                warehouse = warehouse,
                isActive = isActive,
                search = search,
                hasPermission = hasPermission
            )

            val pageable = PageRequest.of(page - 1, limit.coerceAtMost(100))
            val result = userRoleMappingService.getUserRoleMappings(filters, pageable, sortBy, sortOrder)

            ResponseEntity.ok(
                ApiResponse.success(
                    data = result,
                    message = "User role mappings retrieved successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching user role mappings", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<UserRoleMappingListResponse>(
                    message = "Failed to retrieve user role mappings"
                )
            )
        }
    }

    /**
     * API 076: Get Single User Role Mapping
     * Retrieves complete details of a specific user role mapping
     */
    @GetMapping("/user-role-mappings/{email}")
    @Operation(
        summary = "Get Single User Role Mapping",
        description = "Retrieves complete details of a specific user role mapping including all permissions"
    )
    fun getUserRoleMapping(
        @PathVariable email: String
    ): ResponseEntity<ApiResponse<UserRoleMappingDetail>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.debug("Fetching user role mapping for email: $email, client: $clientId")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<UserRoleMappingDetail>(
                        message = "Missing tenant context"
                    )
                )
            }

            val userDetail = userRoleMappingService.getUserRoleMappingDetail(email, clientId)
            if (userDetail != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = userDetail,
                        message = "User role mapping retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<UserRoleMappingDetail>(
                        message = "User not found for this tenant"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching user role mapping", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<UserRoleMappingDetail>(
                    message = "Failed to retrieve user role mapping"
                )
            )
        }
    }

    /**
     * API 077: Update User Role Mapping
     * Updates user role mapping including permissions, warehouses, and active status
     */
    @PutMapping("/user-role-mappings/{email}")
    @Operation(
        summary = "Update User Role Mapping",
        description = "Updates user role mapping including permissions, warehouses, and active status"
    )
    fun updateUserRoleMapping(
        @PathVariable email: String,
        @Valid @RequestBody request: UpdateUserRoleMappingRequest
    ): ResponseEntity<ApiResponse<UserRoleMappingUpdateResponse>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.info("Updating user role mapping for email: $email, client: $clientId")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<UserRoleMappingUpdateResponse>(
                        message = "Missing tenant context"
                    )
                )
            }

            val updated = userRoleMappingService.updateUserRoleMapping(email, clientId, request)
            if (updated != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = updated,
                        message = "User role mapping updated successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<UserRoleMappingUpdateResponse>(
                        message = "User not found"
                    )
                )
            }
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid update request: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error<UserRoleMappingUpdateResponse>(
                    message = e.message ?: "Invalid request"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating user role mapping", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<UserRoleMappingUpdateResponse>(
                    message = "Failed to update user role mapping"
                )
            )
        }
    }

    /**
     * API 078: Delete User Role Mapping
     * Soft deletes a user role mapping by setting is_active to false
     */
    @DeleteMapping("/user-role-mappings/{email}")
    @Operation(
        summary = "Delete User Role Mapping",
        description = "Soft deletes a user role mapping by setting is_active to false, maintaining audit trail"
    )
    fun deleteUserRoleMapping(
        @PathVariable email: String,
        @RequestParam(required = false, defaultValue = "false") hardDelete: Boolean,
        @RequestHeader(required = false) confirmationHeader: String?
    ): ResponseEntity<ApiResponse<DeletionResponse>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        val requestingUser = "system" // TODO: Get from JWT/security context when implemented
        logger.info("Deleting user role mapping for email: $email, hard: $hardDelete")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<DeletionResponse>(
                        message = "Missing tenant context"
                    )
                )
            }

            // Hard delete requires confirmation header
            if (hardDelete && confirmationHeader != "CONFIRM-DELETE") {
                return ResponseEntity.badRequest().body(
                    ApiResponse.error<DeletionResponse>(
                        message = "Hard delete requires confirmation header"
                    )
                )
            }

            val result = userRoleMappingService.deleteUserRoleMapping(
                email,
                clientId,
                hardDelete,
                requestingUser ?: "system"
            )

            ResponseEntity.ok(
                ApiResponse.success(
                    data = result,
                    message = "User role mapping deleted successfully"
                )
            )
        } catch (e: PermissionDeniedException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error<DeletionResponse>(
                    message = "Insufficient permissions to delete user"
                )
            )
        } catch (e: LastAdminException) {
            ResponseEntity.badRequest().body(
                ApiResponse.error<DeletionResponse>(
                    message = "Cannot delete last admin user"
                )
            )
        } catch (e: Exception) {
            logger.error("Error deleting user role mapping", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<DeletionResponse>(
                    message = "Failed to delete user role mapping"
                )
            )
        }
    }

    /**
     * API 079: Get User's Current Warehouse
     * Retrieves the current warehouse where a user is actively working
     */
    @GetMapping("/user-role-mappings/{email}/current-warehouse")
    @Operation(
        summary = "Get User's Current Warehouse",
        description = "Retrieves the current warehouse where a user is actively working, along with warehouse details"
    )
    fun getUserCurrentWarehouse(
        @PathVariable email: String
    ): ResponseEntity<ApiResponse<CurrentWarehouseResponse>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.debug("Fetching current warehouse for user: $email")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<CurrentWarehouseResponse>(
                        message = "Missing tenant context"
                    )
                )
            }

            val warehouse = userRoleMappingService.getUserCurrentWarehouse(email, clientId)
            if (warehouse != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = warehouse,
                        message = "Current warehouse retrieved successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error<CurrentWarehouseResponse>(
                        message = "User not found or no current warehouse set"
                    )
                )
            }
        } catch (e: InactiveUserException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error<CurrentWarehouseResponse>(
                    message = "User is inactive"
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching current warehouse", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<CurrentWarehouseResponse>(
                    message = "Failed to retrieve current warehouse"
                )
            )
        }
    }

    /**
     * API 080: Update User's Current Warehouse
     * Updates the warehouse where the user is currently working
     */
    @PutMapping("/user-role-mappings/{email}/current-warehouse")
    @Operation(
        summary = "Update User's Current Warehouse",
        description = "Updates the warehouse where the user is currently working, with validation that user has access"
    )
    fun updateUserCurrentWarehouse(
        @PathVariable email: String,
        @Valid @RequestBody request: UpdateCurrentWarehouseRequest
    ): ResponseEntity<ApiResponse<UpdateWarehouseResponse>> {
        val clientId = TenantContext.getCurrentTenant()?.toIntOrNull()
        logger.info("Updating current warehouse for user: $email to ${request.warehouseId}")

        return try {
            if (clientId == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                    ApiResponse.error<UpdateWarehouseResponse>(
                        message = "Missing tenant context"
                    )
                )
            }

            val result = userRoleMappingService.updateUserCurrentWarehouse(
                email,
                clientId,
                request.warehouseId,
                request.reason
            )

            ResponseEntity.ok(
                ApiResponse.success(
                    data = result,
                    message = "Warehouse switched successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(
                ApiResponse.error<UpdateWarehouseResponse>(
                    message = e.message ?: "Invalid warehouse ID format"
                )
            )
        } catch (e: UnauthorizedWarehouseException) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                ApiResponse.error<UpdateWarehouseResponse>(
                    message = "Warehouse not in user's authorized list"
                )
            )
        } catch (e: InactiveWarehouseException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(
                ApiResponse.error<UpdateWarehouseResponse>(
                    message = "Warehouse is not operational"
                )
            )
        } catch (e: Exception) {
            logger.error("Error updating current warehouse", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error<UpdateWarehouseResponse>(
                    message = "Failed to update current warehouse"
                )
            )
        }
    }
}

/**
 * Custom exceptions for user role operations
 */
class DuplicateUserException(message: String) : RuntimeException(message)
class PermissionDeniedException(message: String) : RuntimeException(message)
class LastAdminException(message: String) : RuntimeException(message)
class InactiveUserException(message: String) : RuntimeException(message)
class UnauthorizedWarehouseException(message: String) : RuntimeException(message)
class InactiveWarehouseException(message: String) : RuntimeException(message)