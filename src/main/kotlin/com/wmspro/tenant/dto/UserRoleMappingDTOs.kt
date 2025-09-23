package com.wmspro.tenant.dto

import com.wmspro.tenant.model.Permissions
import com.wmspro.tenant.model.UserRoleMapping
import java.time.LocalDateTime

/**
 * DTOs for User Role Mapping operations
 * APIs 074-080
 */

/**
 * Filters for API 075: Get User Role Mappings List
 */
data class UserRoleMappingFilters(
    val clientId: Int,
    val roleType: String? = null,
    val warehouse: String? = null,
    val isActive: Boolean? = null,
    val search: String? = null,
    val hasPermission: String? = null
)

/**
 * Response for API 075: Get User Role Mappings List
 */
data class UserRoleMappingListResponse(
    val users: List<UserRoleMappingSummary>,
    val page: Int,
    val limit: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val summary: UserRoleMappingStats
)

/**
 * Summary view of user role mapping for list responses
 */
data class UserRoleMappingSummary(
    val email: String,
    val roleType: String,
    val warehouses: List<String>,
    val currentWarehouse: String?,
    val permissionsCount: Int,
    val isActive: Boolean,
    val lastLogin: LocalDateTime?,
    val createdAt: LocalDateTime?
)

/**
 * Statistics for user role mappings
 */
data class UserRoleMappingStats(
    val totalActive: Int,
    val totalInactive: Int,
    val rolesBreakdown: Map<String, Int>
)

/**
 * Detailed response for API 076: Get Single User Role Mapping
 */
data class UserRoleMappingDetail(
    val email: String,
    val roleType: RoleTypeInfo,
    val permissions: Permissions,
    val customPermissions: Map<String, Any>?,
    val warehouses: List<WarehouseInfo>,
    val currentWarehouse: WarehouseInfo?,
    val isActive: Boolean,
    val lastLogin: LocalDateTime?,
    val createdBy: String,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val permissionStats: PermissionStats
)

/**
 * Role type information with name and description
 */
data class RoleTypeInfo(
    val roleType: String,
    val roleName: String?,
    val description: String?
)

/**
 * Warehouse information for user mappings
 */
data class WarehouseInfo(
    val warehouseId: String,
    val warehouseName: String?,
    val warehouseCode: String?
)

/**
 * Permission statistics
 */
data class PermissionStats(
    val grantedCount: Int,
    val operationalCount: Int,
    val managementCount: Int
)

/**
 * Request for API 077: Update User Role Mapping
 */
data class UpdateUserRoleMappingRequest(
    val roleType: String? = null,
    val warehouses: List<String>? = null,
    val currentWarehouse: String? = null,
    val permissions: Permissions? = null,
    val customPermissions: Map<String, Any>? = null,
    val isActive: Boolean? = null
)

/**
 * Response for API 077: Update User Role Mapping
 */
data class UserRoleMappingUpdateResponse(
    val email: String,
    val roleType: String,
    val warehouses: List<String>,
    val currentWarehouse: String?,
    val permissions: Permissions,
    val customPermissions: Map<String, Any>?,
    val isActive: Boolean,
    val updatedAt: LocalDateTime?,
    val changeSummary: ChangeSummary
)

/**
 * Summary of changes made during update
 */
data class ChangeSummary(
    val fieldsModified: List<String>,
    val previousValues: Map<String, Any>,
    val newValues: Map<String, Any>
)

/**
 * Response for API 078: Delete User Role Mapping
 */
data class DeletionResponse(
    val email: String,
    val deletionType: String, // "soft" or "hard"
    val deactivatedAt: LocalDateTime,
    val deactivatedBy: String,
    val message: String
)

/**
 * Response for API 079: Get User's Current Warehouse
 */
data class CurrentWarehouseResponse(
    val warehouseId: String,
    val warehouseName: String,
    val warehouseCode: String,
    val address: WarehouseAddress,
    val operationalStatus: String,
    val zoneCount: Int?,
    val totalBins: Int?,
    val userPermissions: WarehousePermissions,
    val isAuthorized: Boolean,
    val lastSelectedAt: LocalDateTime?
)

/**
 * Warehouse address details
 */
data class WarehouseAddress(
    val street: String,
    val city: String,
    val state: String?,
    val zip: String,
    val country: String
)

/**
 * Warehouse-specific permissions
 */
data class WarehousePermissions(
    val canOffload: Boolean,
    val canReceive: Boolean,
    val canPutaway: Boolean,
    val canPick: Boolean,
    val canPackMove: Boolean,
    val canPickPackMove: Boolean,
    val canLoad: Boolean,
    val canCount: Boolean,
    val canTransfer: Boolean
)

/**
 * Request for API 080: Update User's Current Warehouse
 */
data class UpdateCurrentWarehouseRequest(
    val warehouseId: String,
    val reason: String? = null
)

/**
 * Response for API 080: Update User's Current Warehouse
 */
data class UpdateWarehouseResponse(
    val warehouseId: String,
    val warehouseName: String,
    val previousWarehouse: PreviousWarehouseInfo?,
    val changeTimestamp: LocalDateTime,
    val userEmail: String,
    val isAuthorized: Boolean,
    val warehouseDetails: WarehouseDetails,
    val message: String
)

/**
 * Previous warehouse information
 */
data class PreviousWarehouseInfo(
    val warehouseId: String,
    val warehouseName: String
)

/**
 * Warehouse details for update response
 */
data class WarehouseDetails(
    val address: WarehouseAddress,
    val status: String,
    val zoneCount: Int?
)