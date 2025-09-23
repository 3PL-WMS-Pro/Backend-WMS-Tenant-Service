package com.wmspro.tenant.dto

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.tenant.model.MobileFeature
import com.wmspro.tenant.model.RoleType

/**
 * Minimal DTOs for RoleType operations
 * Uses models directly where possible
 */

/**
 * For creating new roles - same as RoleType but without roleCode (which is generated)
 */
data class CreateRoleRequest(
    val roleName: String,
    val displayName: String,
    val description: String? = null,
    val isSystemRole: Boolean = false,
    val hierarchyLevel: Int,
    val defaultPermissions: PermissionsSchema,
    val menuAccess: List<String> = emptyList(),
    val apiAccessPatterns: List<String> = emptyList(),
    val mobileFeatures: List<MobileFeature> = emptyList()
) {
    fun toRoleType(roleCode: String): RoleType {
        return RoleType(
            roleCode = roleCode,
            roleName = roleName,
            displayName = displayName,
            description = description,
            isSystemRole = isSystemRole,
            hierarchyLevel = hierarchyLevel,
            defaultPermissions = defaultPermissions,
            menuAccess = menuAccess,
            apiAccessPatterns = apiAccessPatterns,
            mobileFeatures = mobileFeatures
        )
    }
}

/**
 * For partial updates - allows updating specific fields only
 */
data class UpdateRoleTypeRequest(
    val displayName: String? = null,
    val description: String? = null,
    val hierarchyLevel: Int? = null,
    val defaultPermissions: PermissionsSchema? = null,
    val menuAccess: List<String>? = null,
    val apiAccessPatterns: List<String>? = null,
    val mobileFeatures: List<String>? = null
)

/**
 * Summary view for listing roles
 */
data class RoleTypeSummary(
    val roleCode: String,
    val roleName: String,
    val displayName: String,
    val hierarchyLevel: Int,
    val isSystemRole: Boolean,
    val userCount: Int? = null
)

/**
 * Extension function for summary view
 */
fun RoleType.toSummary(userCount: Int? = null): RoleTypeSummary {
    return RoleTypeSummary(
        roleCode = this.roleCode,
        roleName = this.roleName,
        displayName = this.displayName,
        hierarchyLevel = this.hierarchyLevel,
        isSystemRole = this.isSystemRole,
        userCount = userCount
    )
}