package com.wmspro.tenant.service

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.controller.*
import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.Permissions
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.repository.UserRoleMappingRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing user role mappings
 * Implements business logic for APIs 074-080
 * This service operates on tenant-specific databases
 */
@Service
class UserRoleMappingService(
    private val userRoleMappingRepository: UserRoleMappingRepository,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(UserRoleMappingService::class.java)

    /**
     * API 074: Create User Role Mapping
     */
    @Transactional
    fun createUserRoleMapping(clientId: Int, request: CreateUserRoleMappingRequest): UserRoleMapping {
        logger.info("Creating user role mapping for email: ${request.email}")

        // Normalize email
        val normalizedEmail = request.email.lowercase().trim()

        // Check if user already exists for this client
        if (userRoleMappingRepository.existsByEmail(normalizedEmail)) {
            throw DuplicateUserException("User role mapping already exists for email: $normalizedEmail")
        }

        // Validate warehouses
        if (request.warehouses.isEmpty()) {
            throw IllegalArgumentException("At least one warehouse must be assigned")
        }

        // Generate next user role code (UR-XXX)
        val nextCode = userRoleMappingRepository.findTopByOrderByUserRoleCodeDesc()
            .map { it.userRoleCode }
            .map { code ->
                val number = code.substringAfter("UR-").toIntOrNull() ?: 0
                "UR-${(number + 1).toString().padStart(3, '0')}"
            }
            .orElse("UR-001")

        // Build entity with defaults derived from role
        var mapping = UserRoleMapping.createWithRole(
            userRoleCode = nextCode,
            email = normalizedEmail,
            clientId = clientId,
            roleCode = request.roleCode,
            warehouses = request.warehouses,
            createdBy = request.createdBy
        )

        // Override current warehouse if provided
        request.currentWarehouse?.let { current ->
            if (!request.warehouses.contains(current)) {
                throw IllegalArgumentException("Current warehouse must be in warehouses list")
            }
            mapping = mapping.copy(currentWarehouse = current)
        }

        // Apply optional flags
        request.customPermissions?.let { custom ->
            mapping = mapping.copy(customPermissions = custom)
        }
        request.isActive?.let { active ->
            mapping = mapping.copy(isActive = active)
        }

        val saved = userRoleMappingRepository.save(mapping)
        logger.info("Successfully created user role mapping for: ${saved.email}")
        return saved
    }

    /**
     * API 075: Get User Role Mappings List
     */
    fun getUserRoleMappings(
        filters: UserRoleMappingFilters,
        pageable: Pageable,
        sortBy: String?,
        sortOrder: String
    ): UserRoleMappingListResponse {
        logger.debug("Fetching user role mappings with filters: $filters")

        // Build query
        val query = Query()
        val criteria = Criteria.where("clientId").`is`(filters.clientId)

        // Apply filters
        filters.roleType?.let { criteria.and("roleType").`is`(it) }
        filters.warehouse?.let { criteria.and("warehouses").`in`(it) }
        filters.isActive?.let { criteria.and("isActive").`is`(it) }
        filters.search?.let {
            criteria.and("email").regex(it, "i") // Case-insensitive search
        }

        // Handle permission filter
        filters.hasPermission?.let { permission ->
            val parts = permission.split("=")
            if (parts.size == 2) {
                val field = "permissions.${parts[0]}"
                val value = parts[1].toBoolean()
                criteria.and(field).`is`(value)
            }
        }

        query.addCriteria(criteria)

        // Apply sorting
        if (!sortBy.isNullOrBlank()) {
            val sort = if (sortOrder == "desc") {
                org.springframework.data.domain.Sort.by(sortBy).descending()
            } else {
                org.springframework.data.domain.Sort.by(sortBy).ascending()
            }
            query.with(sort)
        }

        // Apply pagination
        query.with(pageable)

        // Execute query
        val users = mongoTemplate.find(query, UserRoleMapping::class.java)
        val totalItems = mongoTemplate.count(Query(criteria), UserRoleMapping::class.java)

        // Get statistics
        val activeCount = mongoTemplate.count(
            Query(Criteria.where("clientId").`is`(filters.clientId).and("isActive").`is`(true)),
            UserRoleMapping::class.java
        ).toInt()

        val inactiveCount = mongoTemplate.count(
            Query(Criteria.where("clientId").`is`(filters.clientId).and("isActive").`is`(false)),
            UserRoleMapping::class.java
        ).toInt()

        // Role breakdown
        val rolesBreakdown = users.groupBy { it.roleCode }
            .mapValues { it.value.size }

        // Convert to response DTOs
        val summaries = users.map { user ->
            UserRoleMappingSummary(
                email = user.email,
                roleType = user.roleCode,
                warehouses = user.warehouses,
                currentWarehouse = user.currentWarehouse,
                permissionsCount = countTruePermissions(convertPermissionsSchemaToPermissions(user.permissions)),
                isActive = user.isActive,
                lastLogin = user.lastLogin,
                createdAt = user.createdAt
            )
        }

        return UserRoleMappingListResponse(
            users = summaries,
            page = pageable.pageNumber + 1,
            limit = pageable.pageSize,
            totalItems = totalItems,
            totalPages = (totalItems / pageable.pageSize + if (totalItems % pageable.pageSize > 0) 1 else 0).toInt(),
            hasNext = pageable.pageNumber < (totalItems / pageable.pageSize),
            hasPrevious = pageable.pageNumber > 0,
            summary = UserRoleMappingStats(
                totalActive = activeCount,
                totalInactive = inactiveCount,
                rolesBreakdown = rolesBreakdown
            )
        )
    }

    /**
     * API 076: Get Single User Role Mapping
     */
    fun getUserRoleMappingDetail(email: String, clientId: Int): UserRoleMappingDetail? {
        logger.debug("Fetching user role mapping for email: $email")

        val normalizedEmail = email.lowercase().trim()
        val user = userRoleMappingRepository.findByEmail(normalizedEmail).firstOrNull()
            ?: return null

        // Calculate permission stats
        val convertedPermissions = convertPermissionsSchemaToPermissions(user.permissions)
        val permissionStats = PermissionStats(
            grantedCount = countTruePermissions(convertedPermissions),
            operationalCount = countOperationalPermissions(convertedPermissions),
            managementCount = countManagementPermissions(convertedPermissions)
        )

        // Build warehouse info (simplified - in production would fetch from Warehouse service)
        val warehouseInfos = user.warehouses.map { warehouseId ->
            WarehouseInfo(
                warehouseId = warehouseId,
                warehouseName = "Warehouse $warehouseId", // Would fetch real name
                warehouseCode = "WH-$warehouseId"
            )
        }

        val currentWarehouseInfo = user.currentWarehouse?.let { currentId ->
            warehouseInfos.find { it.warehouseId == currentId }
        }

        return UserRoleMappingDetail(
            email = user.email,
            roleType = RoleTypeInfo(
                roleType = user.roleCode,
                roleName = user.roleCode, // Would fetch from role configuration
                description = "Role description for ${user.roleCode}"
            ),
            permissions = convertedPermissions,
            customPermissions = user.customPermissions,
            warehouses = warehouseInfos,
            currentWarehouse = currentWarehouseInfo,
            isActive = user.isActive,
            lastLogin = user.lastLogin,
            createdBy = user.createdBy ?: "system",
            createdAt = user.createdAt,
            updatedAt = user.updatedAt,
            permissionStats = permissionStats
        )
    }

    /**
     * API 077: Update User Role Mapping
     */
    @Transactional
    fun updateUserRoleMapping(
        email: String,
        clientId: Int,
        request: UpdateUserRoleMappingRequest
    ): UserRoleMappingUpdateResponse? {
        logger.info("Updating user role mapping for email: $email")

        val normalizedEmail = email.lowercase().trim()
        val existing = userRoleMappingRepository.findByEmail(normalizedEmail).firstOrNull()
            ?: return null

        val fieldsModified = mutableListOf<String>()
        val previousValues = mutableMapOf<String, Any>()
        val newValues = mutableMapOf<String, Any>()

        var updated = existing

        // Update role type
        request.roleType?.let { newRole ->
            if (existing.roleCode != newRole) {
                fieldsModified.add("roleType")
                previousValues["roleType"] = existing.roleCode
                newValues["roleType"] = newRole
                updated = updated.copy(roleCode = newRole)
            }
        }

        // Update warehouses
        request.warehouses?.let { newWarehouses ->
            if (existing.warehouses != newWarehouses) {
                fieldsModified.add("warehouses")
                previousValues["warehouses"] = existing.warehouses
                newValues["warehouses"] = newWarehouses
                updated = updated.copy(warehouses = newWarehouses)

                // Validate current warehouse is still in list
                if (!newWarehouses.contains(updated.currentWarehouse)) {
                    updated = updated.copy(currentWarehouse = newWarehouses.firstOrNull())
                }
            }
        }

        // Update current warehouse
        request.currentWarehouse?.let { newCurrent ->
            if (!updated.warehouses.contains(newCurrent)) {
                throw IllegalArgumentException("Current warehouse not in warehouses list")
            }
            if (existing.currentWarehouse != newCurrent) {
                fieldsModified.add("currentWarehouse")
                previousValues["currentWarehouse"] = existing.currentWarehouse ?: ""
                newValues["currentWarehouse"] = newCurrent
                updated = updated.copy(currentWarehouse = newCurrent)
            }
        }

        // Update permissions
        request.permissions?.let { newPermissions ->
            fieldsModified.add("permissions")
            previousValues["permissions"] = existing.permissions
            newValues["permissions"] = newPermissions
            // Convert Permissions to PermissionsSchema
            val newSchema = convertPermissionsToPermissionsSchema(newPermissions)
            updated = updated.copy(permissions = newSchema)
        }

        // Update custom permissions
        request.customPermissions?.let { newCustom ->
            fieldsModified.add("customPermissions")
            previousValues["customPermissions"] = existing.customPermissions
            newValues["customPermissions"] = newCustom
            // Convert Map<String, Any> to Map<String, Boolean>
            val booleanMap = newCustom.mapValues { it.value as? Boolean ?: false }
            updated = updated.copy(customPermissions = booleanMap)
        }

        // Update active status
        request.isActive?.let { newActive ->
            if (existing.isActive != newActive) {
                fieldsModified.add("isActive")
                previousValues["isActive"] = existing.isActive
                newValues["isActive"] = newActive
                updated = updated.copy(isActive = newActive)
            }
        }

        // Update timestamps
        updated = updated.copy(updatedAt = LocalDateTime.now())

        val saved = userRoleMappingRepository.save(updated)

        val changeSummary = ChangeSummary(
            fieldsModified = fieldsModified,
            previousValues = previousValues,
            newValues = newValues
        )

        return UserRoleMappingUpdateResponse(
            email = saved.email,
            roleType = saved.roleCode,
            warehouses = saved.warehouses,
            currentWarehouse = saved.currentWarehouse,
            permissions = convertPermissionsSchemaToPermissions(saved.permissions),
            customPermissions = saved.customPermissions,
            isActive = saved.isActive,
            updatedAt = saved.updatedAt,
            changeSummary = changeSummary
        )
    }

    /**
     * API 078: Delete User Role Mapping
     */
    @Transactional
    fun deleteUserRoleMapping(
        email: String,
        clientId: Int,
        hardDelete: Boolean,
        requestingUser: String
    ): DeletionResponse {
        logger.info("Deleting user role mapping for email: $email (hard: $hardDelete)")

        val normalizedEmail = email.lowercase().trim()
        val existing = userRoleMappingRepository.findByEmail(normalizedEmail).firstOrNull()
            ?: throw IllegalArgumentException("User not found")

        // Check if last admin (simplified - in production would check role permissions)
        if (existing.roleCode == "ROLE-001") {
            val adminCount = mongoTemplate.count(
                Query(Criteria.where("clientId").`is`(clientId)
                    .and("roleType").`is`("ADMIN")
                    .and("isActive").`is`(true)),
                UserRoleMapping::class.java
            )
            if (adminCount <= 1) {
                throw LastAdminException("Cannot delete last admin user")
            }
        }

        val now = LocalDateTime.now()

        return if (hardDelete) {
            userRoleMappingRepository.delete(existing)
            DeletionResponse(
                email = normalizedEmail,
                deletionType = "hard",
                deactivatedAt = now,
                deactivatedBy = requestingUser,
                message = "User permanently deleted"
            )
        } else {
            val deactivated = existing.copy(isActive = false, updatedAt = now)
            userRoleMappingRepository.save(deactivated)
            DeletionResponse(
                email = normalizedEmail,
                deletionType = "soft",
                deactivatedAt = now,
                deactivatedBy = requestingUser,
                message = "User deactivated successfully"
            )
        }
    }

    /**
     * API 079: Get User's Current Warehouse
     */
    fun getUserCurrentWarehouse(email: String, clientId: Int): CurrentWarehouseResponse? {
        logger.debug("Fetching current warehouse for user: $email")

        val normalizedEmail = email.lowercase().trim()
        val user = userRoleMappingRepository.findByEmail(normalizedEmail).firstOrNull()
            ?: return null

        if (!user.isActive) {
            throw InactiveUserException("User is inactive")
        }

        if (user.currentWarehouse.isNullOrBlank()) {
            return null
        }

        // In production, would fetch warehouse details from Warehouse service
        // For now, returning simplified response
        return CurrentWarehouseResponse(
            warehouseCode = user.currentWarehouse!!,  // warehouseId and code are the same
            warehouseName = "Warehouse ${user.currentWarehouse}"
        )
    }

    /**
     * API 080: Update User's Current Warehouse
     */
    @Transactional
    fun updateUserCurrentWarehouse(
        email: String,
        clientId: Int,
        warehouseId: String,
        reason: String?
    ): UpdateWarehouseResponse {
        logger.info("Updating current warehouse for user: $email to $warehouseId")

        val normalizedEmail = email.lowercase().trim()
        val user = userRoleMappingRepository.findByEmail(normalizedEmail).firstOrNull()
            ?: throw IllegalArgumentException("User not found")

        if (!user.isActive) {
            throw InactiveUserException("User is inactive")
        }

        // Check if warehouse is in user's authorized list
        if (!user.warehouses.contains(warehouseId)) {
            throw UnauthorizedWarehouseException("Warehouse not in user's authorized list")
        }

        // In production, would check if warehouse is operational
        // For now, assuming all warehouses are operational

        val previousWarehouse = user.currentWarehouse?.let { prevId ->
            PreviousWarehouseInfo(
                warehouseId = prevId,
                warehouseName = "Warehouse $prevId"
            )
        }

        // Update user's current warehouse
        val updated = user.copy(
            currentWarehouse = warehouseId,
            updatedAt = LocalDateTime.now()
        )
        userRoleMappingRepository.save(updated)

        return UpdateWarehouseResponse(
            warehouseId = warehouseId,
            warehouseName = "Warehouse $warehouseId",
            previousWarehouse = previousWarehouse,
            changeTimestamp = LocalDateTime.now(),
            userEmail = normalizedEmail,
            isAuthorized = true,
            warehouseDetails = WarehouseDetails(
                status = "ACTIVE",
                zoneCount = 10
            ),
            message = "Warehouse switched successfully${reason?.let { " - Reason: $it" } ?: ""}"
        )
    }

    // Helper functions
    private fun countTruePermissions(permissions: Permissions): Int {
        var count = 0
        // Operational permissions
        if (permissions.canOffload) count++
        if (permissions.canReceive) count++
        if (permissions.canPutaway) count++
        if (permissions.canPick) count++
        if (permissions.canPackMove) count++
        if (permissions.canPickPackMove) count++
        if (permissions.canLoad) count++
        if (permissions.canCount) count++
        if (permissions.canTransfer) count++
        if (permissions.canAdjustInventory) count++
        // Management permissions
        if (permissions.canViewReports) count++
        if (permissions.canManageUsers) count++
        if (permissions.canManageWarehouses) count++
        if (permissions.canConfigureSettings) count++
        if (permissions.canViewBilling) count++
        // System permissions
        if (permissions.canAccessApi) count++
        if (permissions.canUseMobileApp) count++
        if (permissions.canExportData) count++
        return count
    }

    private fun countOperationalPermissions(permissions: Permissions): Int {
        var count = 0
        if (permissions.canOffload) count++
        if (permissions.canReceive) count++
        if (permissions.canPutaway) count++
        if (permissions.canPick) count++
        if (permissions.canPackMove) count++
        if (permissions.canPickPackMove) count++
        if (permissions.canLoad) count++
        if (permissions.canCount) count++
        if (permissions.canTransfer) count++
        if (permissions.canAdjustInventory) count++
        return count
    }

    private fun countManagementPermissions(permissions: Permissions): Int {
        var count = 0
        if (permissions.canViewReports) count++
        if (permissions.canManageUsers) count++
        if (permissions.canManageWarehouses) count++
        if (permissions.canConfigureSettings) count++
        if (permissions.canViewBilling) count++
        return count
    }

    /**
     * Converts Permissions to PermissionsSchema for model storage
     */
    private fun convertPermissionsToPermissionsSchema(permissions: Permissions): PermissionsSchema {
        return PermissionsSchema(
            canOffload = permissions.canOffload,
            canReceive = permissions.canReceive,
            canPutaway = permissions.canPutaway,
            canPick = permissions.canPick,
            canPackMove = permissions.canPackMove,
            canPickPackMove = permissions.canPickPackMove,
            canLoad = permissions.canLoad,
            canCount = permissions.canCount,
            canTransfer = permissions.canTransfer,
            canAdjustInventory = permissions.canAdjustInventory,
            canViewReports = permissions.canViewReports,
            canManageUsers = permissions.canManageUsers,
            canManageWarehouses = permissions.canManageWarehouses,
            canConfigureSettings = permissions.canConfigureSettings,
            canViewBilling = permissions.canViewBilling,
            canAccessApi = permissions.canAccessApi,
            canUseMobileApp = permissions.canUseMobileApp,
            canExportData = permissions.canExportData
        )
    }

    /**
     * Converts PermissionsSchema to Permissions for DTOs
     */
    private fun convertPermissionsSchemaToPermissions(schema: PermissionsSchema): Permissions {
        return Permissions(
            canOffload = schema.canOffload,
            canReceive = schema.canReceive,
            canPutaway = schema.canPutaway,
            canPick = schema.canPick,
            canPackMove = schema.canPackMove,
            canPickPackMove = schema.canPickPackMove,
            canLoad = schema.canLoad,
            canCount = schema.canCount,
            canTransfer = schema.canTransfer,
            canManageUsers = schema.canManageUsers,
            canManageInventory = schema.canAdjustInventory,
            canManageOrders = false, // Not in PermissionsSchema
            canManageWarehouses = schema.canManageWarehouses,
            canManageReports = schema.canViewReports,
            canViewAnalytics = schema.canViewReports,
            canExportData = schema.canExportData,
            canConfigureSettings = schema.canConfigureSettings,
            canAuditOperations = false, // Not in PermissionsSchema
            isAdmin = false, // Determined by role
            isSupervisor = false // Determined by role
        )
    }

    // Legacy methods for compatibility (no-ops / placeholders)
    fun assignRoleToUser(mapping: UserRoleMapping) { /* no-op: deprecated */ }
    fun getUserRoles(userId: String) = listOf<UserRoleMapping>() // Deprecated
    fun getEffectivePermissions(userId: String) = mapOf<String, Any>() // Deprecated
    fun updateUserRole(userId: String, roleCode: String, mapping: UserRoleMapping) = mapping // Deprecated
    fun removeRoleFromUser(userId: String, roleCode: String) { /* no-op: deprecated */ }
    fun getUsersByRole(roleCode: String) = listOf<UserRoleMapping>() // Deprecated
    fun getUsersByTeam(teamCode: String) = listOf<UserRoleMapping>() // Deprecated
}