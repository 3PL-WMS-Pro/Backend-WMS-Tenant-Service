package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.AssignmentStrategy
import com.wmspro.tenant.model.AutoAssignmentConfig
import com.wmspro.tenant.model.SlaSettings
import com.wmspro.tenant.model.TaskConfigurations
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing task management configurations
 */
@Service
class TaskManagementConfigService(
    private val tenantRepository: TenantDatabaseMappingRepository
) {
    private val logger = LoggerFactory.getLogger(TaskManagementConfigService::class.java)

    /**
     * Get task management configuration
     */
    fun getTaskManagementConfig(tenantId: Int): TaskManagementConfigResponse {
        logger.debug("Fetching task management config for tenant: $tenantId")

        val tenant = tenantRepository.findByClientId(tenantId).orElseThrow {
            IllegalArgumentException("Tenant not found with ID: $tenantId")
        }

        val taskConfig = tenant.tenantSettings.taskConfigurations

        return TaskManagementConfigResponse(
            slaSettings = toSlaSettingsDto(taskConfig.slaSettings),
            autoAssignment = toAutoAssignmentDto(taskConfig.autoAssignment)
        )
    }

    /**
     * Update task management configuration
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#tenantId")
    fun updateTaskManagementConfig(tenantId: Int, request: TaskManagementConfigRequest): TaskManagementConfigResponse {
        logger.info("Updating task management config for tenant: $tenantId")

        val tenant = tenantRepository.findByClientId(tenantId).orElseThrow {
            IllegalArgumentException("Tenant not found with ID: $tenantId")
        }

        // Convert DTOs to domain models
        val slaSettings = toSlaSettings(request.slaSettings)
        val autoAssignment = toAutoAssignmentConfig(request.autoAssignment)

        // Update task configurations
        val updatedTaskConfig = tenant.tenantSettings.taskConfigurations.copy(
            slaSettings = slaSettings,
            autoAssignment = autoAssignment
        )

        val updatedTenantSettings = tenant.tenantSettings.copy(
            taskConfigurations = updatedTaskConfig
        )

        val updatedTenant = tenant.copy(
            tenantSettings = updatedTenantSettings,
            lastConnected = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        val saved = tenantRepository.save(updatedTenant)
        logger.info("Task management config updated for tenant: $tenantId")

        return TaskManagementConfigResponse(
            slaSettings = request.slaSettings,
            autoAssignment = request.autoAssignment
        )
    }

    /**
     * Reset task management configuration to defaults
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#tenantId")
    fun resetToDefaults(tenantId: Int): TaskManagementConfigResponse {
        logger.info("Resetting task management config to defaults for tenant: $tenantId")

        val tenant = tenantRepository.findByClientId(tenantId).orElseThrow {
            IllegalArgumentException("Tenant not found with ID: $tenantId")
        }

        // Create default configurations
        val defaultSlaSettings = SlaSettings()
        val defaultAutoAssignment = AutoAssignmentConfig()

        val updatedTaskConfig = tenant.tenantSettings.taskConfigurations.copy(
            slaSettings = defaultSlaSettings,
            autoAssignment = defaultAutoAssignment
        )

        val updatedTenantSettings = tenant.tenantSettings.copy(
            taskConfigurations = updatedTaskConfig
        )

        val updatedTenant = tenant.copy(
            tenantSettings = updatedTenantSettings,
            lastConnected = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        tenantRepository.save(updatedTenant)
        logger.info("Task management config reset to defaults for tenant: $tenantId")

        return TaskManagementConfigResponse(
            slaSettings = toSlaSettingsDto(defaultSlaSettings),
            autoAssignment = toAutoAssignmentDto(defaultAutoAssignment)
        )
    }

    // Conversion methods: DTO to Domain

    private fun toSlaSettings(dto: SlaSettingsDto): SlaSettings {
        return SlaSettings(
            countingSlaMinutes = dto.countingSlaMinutes,
            transferSlaMinutes = dto.transferSlaMinutes,
            offloadingSlaMinutes = dto.offloadingSlaMinutes,
            receivingSlaMinutes = dto.receivingSlaMinutes,
            putawaySlaMinutes = dto.putawaySlaMinutes,
            pickingSlaMinutes = dto.pickingSlaMinutes,
            packMoveSlaMinutes = dto.packMoveSlaMinutes,
            pickPackMoveSlaMinutes = dto.pickPackMoveSlaMinutes,
            loadingSlaMinutes = dto.loadingSlaMinutes,
            escalationAfterMinutes = dto.escalationAfterMinutes
        )
    }

    private fun toAutoAssignmentConfig(dto: AutoAssignmentDto): AutoAssignmentConfig {
        return AutoAssignmentConfig(
            strategy = dto.strategy
        )
    }

    // Conversion methods: Domain to DTO

    private fun toSlaSettingsDto(domain: SlaSettings): SlaSettingsDto {
        return SlaSettingsDto(
            countingSlaMinutes = domain.countingSlaMinutes,
            transferSlaMinutes = domain.transferSlaMinutes,
            offloadingSlaMinutes = domain.offloadingSlaMinutes,
            receivingSlaMinutes = domain.receivingSlaMinutes,
            putawaySlaMinutes = domain.putawaySlaMinutes,
            pickingSlaMinutes = domain.pickingSlaMinutes,
            packMoveSlaMinutes = domain.packMoveSlaMinutes,
            pickPackMoveSlaMinutes = domain.pickPackMoveSlaMinutes,
            loadingSlaMinutes = domain.loadingSlaMinutes,
            escalationAfterMinutes = domain.escalationAfterMinutes
        )
    }

    private fun toAutoAssignmentDto(domain: AutoAssignmentConfig): AutoAssignmentDto {
        return AutoAssignmentDto(
            strategy = domain.strategy
        )
    }
}
