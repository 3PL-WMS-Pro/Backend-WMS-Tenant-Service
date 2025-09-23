package com.wmspro.tenant.service

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.model.RoleType
import com.wmspro.tenant.repository.RoleTypeRepository
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service for managing role types
 * This service operates on tenant-specific databases
 * Uses simplified multi-tenancy with lazy MongoTemplate
 */
@Service
class RoleTypeService(
    private val roleTypeRepository: RoleTypeRepository,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(RoleTypeService::class.java)

    /**
     * Gets the current client ID from tenant context
     */
    private fun requireCurrentClientId(): Int {
        val tenantId = TenantContext.getCurrentTenant()
            ?: throw IllegalStateException("No tenant context has been set for this request")
        return tenantId.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid tenant ID format: $tenantId")
    }

    /**
     * Creates a new role type
     */
    @Transactional
    fun createRole(role: RoleType): RoleType {
        val clientId = requireCurrentClientId()
        logger.info("Creating new role type: ${role.roleName} for client ID: $clientId")

        // Check if role already exists
        if (roleTypeRepository.existsByRoleName(role.roleName)) {
            throw IllegalArgumentException("Role with name ${role.roleName} already exists")
        }

        val savedRole = roleTypeRepository.save(role)
        logger.info("Successfully created role type: ${savedRole.roleCode} for client ID: $clientId")
        return savedRole
    }

    /**
     * Gets a role by role code
     */
    fun getRoleByCode(roleCode: String): RoleType? {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching role type: $roleCode for client ID: $clientId")

        return roleTypeRepository.findByRoleCode(roleCode).orElse(null)
    }

    /**
     * Gets a role by role name
     */
    fun getRoleByName(roleName: String): RoleType? {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching role type by name: $roleName for client ID: $clientId")

        return roleTypeRepository.findByRoleName(roleName).orElse(null)
    }

    /**
     * Updates a role type
     */
    @Transactional
    fun updateRole(roleCode: String, updates: Map<String, Any>): RoleType {
        val clientId = requireCurrentClientId()
        logger.info("Updating role type: $roleCode for client ID: $clientId")

        val existingRole = roleTypeRepository.findByRoleCode(roleCode).orElse(null)
            ?: throw IllegalArgumentException("Role with code $roleCode not found")

        // System roles cannot be modified except for specific fields
        if (existingRole.isSystemRole) {
            validateSystemRoleUpdate(updates)
        }

        var updatedRole = existingRole

        // Apply updates
        updates["displayName"]?.let { displayName ->
            updatedRole = updatedRole.copy(displayName = displayName as String)
        }

        updates["description"]?.let { description ->
            updatedRole = updatedRole.copy(description = description as String)
        }

        updates["hierarchyLevel"]?.let { level ->
            updatedRole = updatedRole.copy(hierarchyLevel = (level as Number).toInt())
        }

        updates["defaultPermissions"]?.let { permissions ->
            if (permissions is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                updatedRole = updatedRole.copy(
                    defaultPermissions = mapToPermissionsSchema(permissions as Map<String, Any>)
                )
            }
        }

        updates["menuAccess"]?.let { menus ->
            if (menus is List<*>) {
                @Suppress("UNCHECKED_CAST")
                updatedRole = updatedRole.copy(menuAccess = menus as List<String>)
            }
        }

        updates["apiAccessPatterns"]?.let { patterns ->
            if (patterns is List<*>) {
                @Suppress("UNCHECKED_CAST")
                updatedRole = updatedRole.copy(apiAccessPatterns = patterns as List<String>)
            }
        }

        val savedRole = roleTypeRepository.save(updatedRole)
        logger.info("Successfully updated role type: $roleCode for client ID: $clientId")
        return savedRole
    }

    /**
     * Deletes a role type
     */
    @Transactional
    fun deleteRole(roleCode: String) {
        val clientId = requireCurrentClientId()
        logger.info("Deleting role type: $roleCode for client ID: $clientId")

        val role = roleTypeRepository.findByRoleCode(roleCode).orElse(null)
            ?: throw IllegalArgumentException("Role with code $roleCode not found")

        if (role.isSystemRole) {
            throw IllegalArgumentException("System roles cannot be deleted")
        }

        roleTypeRepository.deleteByRoleCode(roleCode)
        logger.info("Successfully deleted role type: $roleCode for client ID: $clientId")
    }

    /**
     * Gets all roles for current tenant
     */
    fun getAllRoles(): List<RoleType> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching all role types for client ID: $clientId")

        return roleTypeRepository.findAll()
    }

    /**
     * Gets system roles
     */
    fun getSystemRoles(): List<RoleType> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching system role types for client ID: $clientId")

        return roleTypeRepository.findByIsSystemRole(true)
    }

    /**
     * Gets roles by hierarchy level
     */
    fun getRolesByHierarchy(maxLevel: Int): List<RoleType> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching role types with hierarchy <= $maxLevel for client ID: $clientId")

        return roleTypeRepository.findByHierarchyLevelLessThanEqual(maxLevel)
    }

    /**
     * Generates next role code
     */
    fun generateNextRoleCode(): String {
        val lastRole = roleTypeRepository.findTopByOrderByRoleCodeDesc().orElse(null)
        return if (lastRole != null) {
            val lastNumber = lastRole.roleCode.substring(5).toInt()
            RoleType.generateRoleCode(lastNumber + 1)
        } else {
            RoleType.generateRoleCode(1)
        }
    }

    /**
     * Initializes default system roles for a new tenant
     */
    @Transactional
    fun initializeSystemRoles() {
        val clientId = requireCurrentClientId()
        logger.info("Initializing system roles for client ID: $clientId")

        val existingRoles = roleTypeRepository.findByIsSystemRole(true)
        if (existingRoles.isEmpty()) {
            // Create default system roles
            val systemAdmin = RoleType.createSystemAdminRole()
            val warehouseManager = RoleType.createWarehouseManagerRole()
            val operationalWorker = RoleType.createOperationalWorkerRole()

            roleTypeRepository.saveAll(listOf(systemAdmin, warehouseManager, operationalWorker))
            logger.info("Successfully initialized system roles for client ID: $clientId")
        } else {
            logger.info("System roles already exist for client ID: $clientId")
        }
    }

    /**
     * Validates updates to system roles
     */
    private fun validateSystemRoleUpdate(updates: Map<String, Any>) {
        val restrictedFields = setOf("roleName", "roleCode", "isSystemRole")
        val attemptedRestricted = updates.keys.intersect(restrictedFields)
        if (attemptedRestricted.isNotEmpty()) {
            throw IllegalArgumentException("Cannot modify restricted fields for system roles: $attemptedRestricted")
        }
    }

    /**
     * Converts a map to PermissionsSchema
     */
    private fun mapToPermissionsSchema(map: Map<String, Any>): PermissionsSchema {
        return PermissionsSchema(
            canOffload = map["canOffload"] as? Boolean ?: false,
            canReceive = map["canReceive"] as? Boolean ?: false,
            canPutaway = map["canPutaway"] as? Boolean ?: false,
            canPick = map["canPick"] as? Boolean ?: false,
            canPackMove = map["canPackMove"] as? Boolean ?: false,
            canPickPackMove = map["canPickPackMove"] as? Boolean ?: false,
            canLoad = map["canLoad"] as? Boolean ?: false,
            canCount = map["canCount"] as? Boolean ?: false,
            canTransfer = map["canTransfer"] as? Boolean ?: false,
            canAdjustInventory = map["canAdjustInventory"] as? Boolean ?: false,
            canViewReports = map["canViewReports"] as? Boolean ?: false,
            canManageUsers = map["canManageUsers"] as? Boolean ?: false,
            canManageWarehouses = map["canManageWarehouses"] as? Boolean ?: false,
            canConfigureSettings = map["canConfigureSettings"] as? Boolean ?: false,
            canViewBilling = map["canViewBilling"] as? Boolean ?: false,
            canAccessApi = map["canAccessApi"] as? Boolean ?: true,
            canUseMobileApp = map["canUseMobileApp"] as? Boolean ?: false,
            canExportData = map["canExportData"] as? Boolean ?: false
        )
    }

    /**
     * Finds roles with specific permission
     */
    fun findRolesWithPermission(permissionName: String, value: Boolean): List<RoleType> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding roles with permission $permissionName=$value for client ID: $clientId")

        val query = Query(Criteria.where("defaultPermissions.$permissionName").`is`(value))
        return mongoTemplate.find(query, RoleType::class.java)
    }
}