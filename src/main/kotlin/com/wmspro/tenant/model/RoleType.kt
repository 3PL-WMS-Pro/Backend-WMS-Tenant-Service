package com.wmspro.tenant.model

import com.wmspro.common.schema.PermissionsSchema
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * RoleType Model - Defines system-wide role templates with permissions
 * Collection: role_types
 * Database: Tenant-specific database
 */
@Document(collection = "role_types")
data class RoleType(
    @Id
    val roleCode: String, // Primary key: "ROLE-001", "ROLE-002", etc.

    @Indexed(unique = true)
    val roleName: String, // Unique, uppercase

    val displayName: String,

    val description: String? = null,

    val isSystemRole: Boolean = false,

    val hierarchyLevel: Int, // Min: 1

    val defaultPermissions: PermissionsSchema = PermissionsSchema(),

    val menuAccess: List<String> = emptyList(),

    val apiAccessPatterns: List<String> = emptyList(),

    val mobileFeatures: List<MobileFeature> = emptyList(),

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(hierarchyLevel >= 1) { "Hierarchy level must be at least 1" }
        require(roleCode.matches(Regex("^ROLE-\\d{3}$"))) { "Role code must match pattern ROLE-XXX" }
        require(roleName == roleName.uppercase()) { "Role name must be uppercase" }
    }

    companion object {
        fun generateRoleCode(sequenceNumber: Int): String {
            return "ROLE-${sequenceNumber.toString().padStart(3, '0')}"
        }

        /**
         * Creates a system admin role
         */
        fun createSystemAdminRole(): RoleType {
            return RoleType(
                roleCode = "ROLE-001",
                roleName = "SYSTEM_ADMIN",
                displayName = "System Administrator",
                description = "Full system access with all permissions",
                isSystemRole = true,
                hierarchyLevel = 1,
                defaultPermissions = PermissionsSchema.createAdminPermissions(),
                menuAccess = listOf("*"),
                apiAccessPatterns = listOf("/**"),
                mobileFeatures = MobileFeature.values().toList()
            )
        }

        /**
         * Creates a warehouse manager role
         */
        fun createWarehouseManagerRole(): RoleType {
            return RoleType(
                roleCode = "ROLE-002",
                roleName = "WAREHOUSE_MANAGER",
                displayName = "Warehouse Manager",
                description = "Manages warehouse operations and team",
                isSystemRole = true,
                hierarchyLevel = 2,
                defaultPermissions = PermissionsSchema.createManagerPermissions(),
                menuAccess = listOf(
                    "/dashboard",
                    "/reports",
                    "/team",
                    "/warehouses",
                    "/inventory"
                ),
                apiAccessPatterns = listOf(
                    "/api/reports/**",
                    "/api/teams/**",
                    "/api/warehouses/**",
                    "/api/inventory/**"
                ),
                mobileFeatures = listOf(
                    MobileFeature.TASK_DASHBOARD,
                    MobileFeature.COUNTING,
                    MobileFeature.TRANSFER,
                    MobileFeature.PIN_AUTH
                )
            )
        }

        /**
         * Creates an operational worker role
         */
        fun createOperationalWorkerRole(): RoleType {
            return RoleType(
                roleCode = "ROLE-003",
                roleName = "OPERATIONAL_WORKER",
                displayName = "Operational Worker",
                description = "Performs warehouse operations",
                isSystemRole = true,
                hierarchyLevel = 3,
                defaultPermissions = PermissionsSchema.createOperationalPermissions(),
                menuAccess = listOf(
                    "/dashboard",
                    "/tasks",
                    "/inventory/view"
                ),
                apiAccessPatterns = listOf(
                    "/api/tasks/**",
                    "/api/inventory/view/**"
                ),
                mobileFeatures = listOf(
                    MobileFeature.SCAN,
                    MobileFeature.OFFLOADING,
                    MobileFeature.RECEIVING,
                    MobileFeature.PUT_AWAY,
                    MobileFeature.PICKING,
                    MobileFeature.PACK_MOVE,
                    MobileFeature.PICK_PACK_MOVE,
                    MobileFeature.LOADING,
                    MobileFeature.COUNTING,
                    MobileFeature.TRANSFER,
                    MobileFeature.TASK_DASHBOARD,
                    MobileFeature.PIN_AUTH
                )
            )
        }
    }
}

/**
 * Mobile features aligned with 9 task types plus dashboard and security features
 */
enum class MobileFeature {
    SCAN,
    OFFLOADING,
    RECEIVING,
    PUT_AWAY,
    PICKING,
    PACK_MOVE,
    PICK_PACK_MOVE,
    LOADING,
    COUNTING,
    TRANSFER,
    TASK_DASHBOARD,
    PIN_AUTH
}