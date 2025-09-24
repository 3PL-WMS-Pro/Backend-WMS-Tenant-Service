package com.wmspro.tenant.model

/**
 * Permissions model for User Role Mapping
 * Represents various permissions that can be granted to users
 */
data class Permissions(
    // Operational permissions - Inbound
    val canOffload: Boolean = false,
    val canReceive: Boolean = false,
    val canPutaway: Boolean = false,

    // Operational permissions - Outbound
    val canPick: Boolean = false,
    val canPackMove: Boolean = false,
    val canPickPackMove: Boolean = false,
    val canLoad: Boolean = false,

    // Operational permissions - Inventory
    val canCount: Boolean = false,
    val canTransfer: Boolean = false,
    val canAdjustInventory: Boolean = false,

    // Management permissions
    val canViewReports: Boolean = false,
    val canManageUsers: Boolean = false,
    val canManageWarehouses: Boolean = false,
    val canConfigureSettings: Boolean = false,
    val canViewBilling: Boolean = false,

    // System permissions
    val canAccessApi: Boolean = true,
    val canUseMobileApp: Boolean = false,
    val canExportData: Boolean = false,

    // Additional management permissions (for compatibility)
    val canManageInventory: Boolean = false,
    val canManageOrders: Boolean = false,
    val canManageReports: Boolean = false,
    val canViewAnalytics: Boolean = false,
    val canAuditOperations: Boolean = false,

    // Administrative flags
    val isAdmin: Boolean = false,
    val isSupervisor: Boolean = false
) {
    companion object {
        /**
         * Creates a default permission set based on role type
         */
        fun forRoleType(roleType: String): Permissions {
            return when (roleType.uppercase()) {
                "ADMIN" -> Permissions(
                    canOffload = true,
                    canReceive = true,
                    canPutaway = true,
                    canPick = true,
                    canPackMove = true,
                    canPickPackMove = true,
                    canLoad = true,
                    canCount = true,
                    canTransfer = true,
                    canAdjustInventory = true,
                    canViewReports = true,
                    canManageUsers = true,
                    canManageWarehouses = true,
                    canConfigureSettings = true,
                    canViewBilling = true,
                    canAccessApi = true,
                    canUseMobileApp = true,
                    canExportData = true,
                    canManageInventory = true,
                    canManageOrders = true,
                    canManageReports = true,
                    canViewAnalytics = true,
                    canAuditOperations = true,
                    isAdmin = true,
                    isSupervisor = true
                )
                "SUPERVISOR" -> Permissions(
                    canOffload = true,
                    canReceive = true,
                    canPutaway = true,
                    canPick = true,
                    canPackMove = true,
                    canPickPackMove = true,
                    canLoad = true,
                    canCount = true,
                    canTransfer = true,
                    canAdjustInventory = true,
                    canViewReports = true,
                    canManageInventory = true,
                    canManageOrders = true,
                    canViewAnalytics = true,
                    canExportData = true,
                    canAuditOperations = true,
                    canAccessApi = true,
                    canUseMobileApp = true,
                    isSupervisor = true
                )
                "OPERATOR" -> Permissions(
                    canOffload = true,
                    canReceive = true,
                    canPutaway = true,
                    canPick = true,
                    canPackMove = true,
                    canPickPackMove = true,
                    canLoad = true,
                    canCount = true,
                    canTransfer = true,
                    canAccessApi = true,
                    canUseMobileApp = true
                )
                "VIEWER" -> Permissions(
                    canViewAnalytics = true,
                    canViewReports = true,
                    canAccessApi = true
                )
                else -> Permissions() // Default: no permissions
            }
        }
    }
}