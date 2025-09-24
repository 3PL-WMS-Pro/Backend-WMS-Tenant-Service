package com.wmspro.tenant.dto

import com.wmspro.tenant.model.TenantSettings
import java.time.LocalDateTime

/**
 * DTOs for Tenant Settings operations
 */

/**
 * Response for getting tenant settings
 */
data class TenantSettingsResponse(
    val clientId: Int,
    val tenantSettings: TenantSettings,
    val lastModified: LocalDateTime?,
    val settingsCount: Int,
    val categories: List<String>
)

/**
 * Request for updating tenant settings
 */
data class UpdateTenantSettingsRequest(
    val tenantSettings: TenantSettings,
    val merge: Boolean = true // If true, merge with existing settings. If false, replace.
)