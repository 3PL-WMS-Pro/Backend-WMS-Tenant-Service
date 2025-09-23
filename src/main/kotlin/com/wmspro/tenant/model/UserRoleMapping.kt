package com.wmspro.tenant.model

import com.wmspro.common.schema.PermissionsSchema
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * UserRoleMapping Model - Maps users to roles within tenant organizations
 * Collection: user_role_mappings
 * Database: Tenant-specific database
 */
@Document(collection = "user_role_mappings")
@CompoundIndex(def = "{'email': 1, 'clientId': 1}", unique = true)
data class UserRoleMapping(
    @Id
    val userRoleCode: String, // Primary key: "UR-001", "UR-002", etc.

    val email: String, // Lowercase, trimmed

    val clientId: Int,

    val roleCode: String, // Reference to RoleType.roleCode

    val warehouses: List<String> = emptyList(), // List of warehouses user can operate in

    val currentWarehouse: String? = null, // Currently active/selected warehouse

    val permissions: PermissionsSchema = PermissionsSchema(),

    val customPermissions: Map<String, Boolean> = emptyMap(),

    val isActive: Boolean = true,

    val createdBy: String? = null,

    val lastLogin: LocalDateTime? = null,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(userRoleCode.matches(Regex("^UR-\\d{3}$"))) { "User role code must match pattern UR-XXX" }
        require(email == email.lowercase().trim()) { "Email must be lowercase and trimmed" }
        require(roleCode.matches(Regex("^ROLE-\\d{3}$"))) { "Role code must match pattern ROLE-XXX" }
        if (currentWarehouse != null) {
            require(warehouses.contains(currentWarehouse)) {
                "Current warehouse must be in the list of assigned warehouses"
            }
        }
    }

    companion object {
        fun generateUserRoleCode(sequenceNumber: Int): String {
            return "UR-${sequenceNumber.toString().padStart(3, '0')}"
        }

        /**
         * Creates a user role mapping with inherited permissions from role type
         */
        fun createFromRoleType(
            userRoleCode: String,
            email: String,
            clientId: Int,
            roleType: RoleType,
            warehouses: List<String> = emptyList(),
            createdBy: String? = null
        ): UserRoleMapping {
            return UserRoleMapping(
                userRoleCode = userRoleCode,
                email = email.lowercase().trim(),
                clientId = clientId,
                roleCode = roleType.roleCode,
                warehouses = warehouses,
                currentWarehouse = warehouses.firstOrNull(),
                permissions = roleType.defaultPermissions,
                customPermissions = emptyMap(),
                isActive = true,
                createdBy = createdBy
            )
        }
    }

    /**
     * Updates the last login timestamp
     */
    fun updateLastLogin(): UserRoleMapping {
        return this.copy(lastLogin = LocalDateTime.now())
    }

    /**
     * Changes the current active warehouse
     */
    fun changeWarehouse(warehouseId: String): UserRoleMapping {
        require(warehouses.contains(warehouseId)) {
            "Cannot switch to a warehouse that is not assigned to the user"
        }
        return this.copy(currentWarehouse = warehouseId)
    }

    /**
     * Adds a warehouse to the user's accessible warehouses
     */
    fun addWarehouse(warehouseId: String): UserRoleMapping {
        if (warehouses.contains(warehouseId)) {
            return this
        }
        val updatedWarehouses = warehouses + warehouseId
        return this.copy(
            warehouses = updatedWarehouses,
            currentWarehouse = currentWarehouse ?: warehouseId
        )
    }

    /**
     * Removes a warehouse from the user's accessible warehouses
     */
    fun removeWarehouse(warehouseId: String): UserRoleMapping {
        val updatedWarehouses = warehouses.filter { it != warehouseId }
        val updatedCurrentWarehouse = if (currentWarehouse == warehouseId) {
            updatedWarehouses.firstOrNull()
        } else {
            currentWarehouse
        }
        return this.copy(
            warehouses = updatedWarehouses,
            currentWarehouse = updatedCurrentWarehouse
        )
    }

    /**
     * Applies custom permissions on top of role permissions
     */
    fun applyCustomPermission(permissionKey: String, value: Boolean): UserRoleMapping {
        val updatedCustomPermissions = customPermissions + (permissionKey to value)
        return this.copy(customPermissions = updatedCustomPermissions)
    }

    /**
     * Removes a custom permission
     */
    fun removeCustomPermission(permissionKey: String): UserRoleMapping {
        val updatedCustomPermissions = customPermissions.filterKeys { it != permissionKey }
        return this.copy(customPermissions = updatedCustomPermissions)
    }

    /**
     * Gets effective permissions by merging role permissions with custom permissions
     */
    fun getEffectivePermissions(): PermissionsSchema {
        var effectivePerms = permissions

        // Apply custom permissions overrides
        customPermissions.forEach { (key, value) ->
            when (key) {
                "canOffload" -> effectivePerms = effectivePerms.copy(canOffload = value)
                "canReceive" -> effectivePerms = effectivePerms.copy(canReceive = value)
                "canPutaway" -> effectivePerms = effectivePerms.copy(canPutaway = value)
                "canPick" -> effectivePerms = effectivePerms.copy(canPick = value)
                "canPackMove" -> effectivePerms = effectivePerms.copy(canPackMove = value)
                "canPickPackMove" -> effectivePerms = effectivePerms.copy(canPickPackMove = value)
                "canLoad" -> effectivePerms = effectivePerms.copy(canLoad = value)
                "canCount" -> effectivePerms = effectivePerms.copy(canCount = value)
                "canTransfer" -> effectivePerms = effectivePerms.copy(canTransfer = value)
                "canAdjustInventory" -> effectivePerms = effectivePerms.copy(canAdjustInventory = value)
                "canViewReports" -> effectivePerms = effectivePerms.copy(canViewReports = value)
                "canManageUsers" -> effectivePerms = effectivePerms.copy(canManageUsers = value)
                "canManageWarehouses" -> effectivePerms = effectivePerms.copy(canManageWarehouses = value)
                "canConfigureSettings" -> effectivePerms = effectivePerms.copy(canConfigureSettings = value)
                "canViewBilling" -> effectivePerms = effectivePerms.copy(canViewBilling = value)
                "canAccessApi" -> effectivePerms = effectivePerms.copy(canAccessApi = value)
                "canUseMobileApp" -> effectivePerms = effectivePerms.copy(canUseMobileApp = value)
                "canExportData" -> effectivePerms = effectivePerms.copy(canExportData = value)
            }
        }

        return effectivePerms
    }

    /**
     * Checks if user has a specific permission
     */
    fun hasPermission(permissionKey: String): Boolean {
        val effective = getEffectivePermissions()
        return when (permissionKey) {
            "canOffload" -> effective.canOffload
            "canReceive" -> effective.canReceive
            "canPutaway" -> effective.canPutaway
            "canPick" -> effective.canPick
            "canPackMove" -> effective.canPackMove
            "canPickPackMove" -> effective.canPickPackMove
            "canLoad" -> effective.canLoad
            "canCount" -> effective.canCount
            "canTransfer" -> effective.canTransfer
            "canAdjustInventory" -> effective.canAdjustInventory
            "canViewReports" -> effective.canViewReports
            "canManageUsers" -> effective.canManageUsers
            "canManageWarehouses" -> effective.canManageWarehouses
            "canConfigureSettings" -> effective.canConfigureSettings
            "canViewBilling" -> effective.canViewBilling
            "canAccessApi" -> effective.canAccessApi
            "canUseMobileApp" -> effective.canUseMobileApp
            "canExportData" -> effective.canExportData
            else -> false
        }
    }
}