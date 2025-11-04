package com.wmspro.tenant.dto

import com.wmspro.tenant.model.AssignmentStrategy
import jakarta.validation.constraints.Min

/**
 * DTOs for Task Management Configuration operations
 */

/**
 * Request/Response for Task Management Configuration
 */
data class TaskManagementConfigRequest(
    val slaSettings: SlaSettingsDto,
    val autoAssignment: AutoAssignmentDto
)

/**
 * SLA Settings DTO
 */
data class SlaSettingsDto(
    @field:Min(1, message = "Counting SLA must be at least 1 minute")
    val countingSlaMinutes: Int = 30,

    @field:Min(1, message = "Transfer SLA must be at least 1 minute")
    val transferSlaMinutes: Int = 30,

    @field:Min(1, message = "Offloading SLA must be at least 1 minute")
    val offloadingSlaMinutes: Int = 30,

    @field:Min(1, message = "Receiving SLA must be at least 1 minute")
    val receivingSlaMinutes: Int = 30,

    @field:Min(1, message = "Putaway SLA must be at least 1 minute")
    val putawaySlaMinutes: Int = 30,

    @field:Min(1, message = "Picking SLA must be at least 1 minute")
    val pickingSlaMinutes: Int = 30,

    @field:Min(1, message = "Pack Move SLA must be at least 1 minute")
    val packMoveSlaMinutes: Int = 30,

    @field:Min(1, message = "Pick Pack Move SLA must be at least 1 minute")
    val pickPackMoveSlaMinutes: Int = 30,

    @field:Min(1, message = "Loading SLA must be at least 1 minute")
    val loadingSlaMinutes: Int = 30,

    @field:Min(1, message = "Escalation time must be at least 1 minute")
    val escalationAfterMinutes: Int = 30
)

/**
 * Auto Assignment Configuration DTO
 */
data class AutoAssignmentDto(
    val strategy: AssignmentStrategy = AssignmentStrategy.ROUND_ROBIN
)

/**
 * Response for Task Management Configuration
 */
data class TaskManagementConfigResponse(
    val slaSettings: SlaSettingsDto,
    val autoAssignment: AutoAssignmentDto
)
