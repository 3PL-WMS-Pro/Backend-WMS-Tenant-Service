package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.service.UserRoleMappingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

/**
 * Controller for user role mapping operations
 * Manages user-role assignments in tenant-specific databases
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Role Management", description = "APIs for managing user roles and permissions")
class UserRoleMappingController(
    private val userRoleMappingService: UserRoleMappingService
) {
    private val logger = LoggerFactory.getLogger(UserRoleMappingController::class.java)

    @PostMapping("/roles")
    @Operation(summary = "Assign role to user", description = "Creates a new user-role mapping")
    fun assignRole(
        @Valid @RequestBody mapping: UserRoleMapping
    ): ResponseEntity<ApiResponse<UserRoleMapping>> {
        logger.info("Assigning role ${mapping.roleCode} to user ${mapping.userId}")

        return try {
            val created = userRoleMappingService.assignRoleToUser(mapping)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(
                    data = created,
                    message = "Role assigned successfully"
                )
            )
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid role assignment: ${e.message}")
            ResponseEntity.badRequest().body(
                ApiResponse.error(
                    message = e.message ?: "Invalid role assignment"
                )
            )
        } catch (e: Exception) {
            logger.error("Error assigning role", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to assign role"
                )
            )
        }
    }

    @GetMapping("/{userId}/roles")
    @Operation(summary = "Get user roles", description = "Retrieves all roles for a user")
    fun getUserRoles(
        @PathVariable userId: String
    ): ResponseEntity<ApiResponse<List<UserRoleMapping>>> {
        logger.info("Fetching roles for user: $userId")

        val roles = userRoleMappingService.getUserRoles(userId)
        return ResponseEntity.ok(
            ApiResponse.success(
                data = roles,
                message = "User roles retrieved successfully"
            )
        )
    }

    @GetMapping("/{userId}/effective-permissions")
    @Operation(summary = "Get effective permissions", description = "Retrieves merged permissions for a user")
    fun getEffectivePermissions(
        @PathVariable userId: String
    ): ResponseEntity<ApiResponse<Any>> {
        logger.info("Fetching effective permissions for user: $userId")

        val permissions = userRoleMappingService.getEffectivePermissions(userId)
        return ResponseEntity.ok(
            ApiResponse.success(
                data = permissions,
                message = "Effective permissions retrieved successfully"
            )
        )
    }

    @PutMapping("/{userId}/roles/{roleCode}")
    @Operation(summary = "Update user role", description = "Updates an existing user-role mapping")
    fun updateUserRole(
        @PathVariable userId: String,
        @PathVariable roleCode: String,
        @Valid @RequestBody mapping: UserRoleMapping
    ): ResponseEntity<ApiResponse<UserRoleMapping>> {
        logger.info("Updating role $roleCode for user $userId")

        return try {
            val updated = userRoleMappingService.updateUserRole(userId, roleCode, mapping)
            if (updated != null) {
                ResponseEntity.ok(
                    ApiResponse.success(
                        data = updated,
                        message = "Role updated successfully"
                    )
                )
            } else {
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ApiResponse.error(
                        message = "User role mapping not found"
                    )
                )
            }
        } catch (e: Exception) {
            logger.error("Error updating user role", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to update role"
                )
            )
        }
    }

    @DeleteMapping("/{userId}/roles/{roleCode}")
    @Operation(summary = "Remove role from user", description = "Removes a role assignment from user")
    fun removeRole(
        @PathVariable userId: String,
        @PathVariable roleCode: String
    ): ResponseEntity<ApiResponse<Void>> {
        logger.info("Removing role $roleCode from user $userId")

        return try {
            userRoleMappingService.removeRoleFromUser(userId, roleCode)
            ResponseEntity.ok(
                ApiResponse.success(
                    data = null,
                    message = "Role removed successfully"
                )
            )
        } catch (e: Exception) {
            logger.error("Error removing role", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error(
                    message = e.message ?: "Failed to remove role"
                )
            )
        }
    }

    @GetMapping("/by-role/{roleCode}")
    @Operation(summary = "Get users by role", description = "Retrieves all users with a specific role")
    fun getUsersByRole(
        @PathVariable roleCode: String
    ): ResponseEntity<ApiResponse<List<UserRoleMapping>>> {
        logger.info("Fetching users with role: $roleCode")

        val users = userRoleMappingService.getUsersByRole(roleCode)
        return ResponseEntity.ok(
            ApiResponse.success(
                data = users,
                message = "Users retrieved successfully"
            )
        )
    }

    @GetMapping("/by-team/{teamCode}")
    @Operation(summary = "Get users by team", description = "Retrieves all users in a specific team")
    fun getUsersByTeam(
        @PathVariable teamCode: String
    ): ResponseEntity<ApiResponse<List<UserRoleMapping>>> {
        logger.info("Fetching users in team: $teamCode")

        val users = userRoleMappingService.getUsersByTeam(teamCode)
        return ResponseEntity.ok(
            ApiResponse.success(
                data = users,
                message = "Team users retrieved successfully"
            )
        )
    }
}