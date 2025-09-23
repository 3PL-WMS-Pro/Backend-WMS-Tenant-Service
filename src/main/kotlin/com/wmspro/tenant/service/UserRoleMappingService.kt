package com.wmspro.tenant.service

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.config.MultiTenantMongoTemplate
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.repository.RoleTypeRepository
import com.wmspro.tenant.repository.UserRoleMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing user role mappings
 * This service operates on tenant-specific databases
 */
@Service
class UserRoleMappingService(
    private val userRoleMappingRepository: UserRoleMappingRepository,
    private val roleTypeRepository: RoleTypeRepository,
    private val multiTenantMongoTemplate: MultiTenantMongoTemplate
) {
    private val logger = LoggerFactory.getLogger(UserRoleMappingService::class.java)

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
     * Creates a new user role mapping
     */
    @Transactional
    fun createUserRoleMapping(mapping: UserRoleMapping): UserRoleMapping {
        val clientId = requireCurrentClientId()
        logger.info("Creating user role mapping for ${mapping.email} with role ${mapping.roleCode} for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            // Check if user already has a role mapping
            if (userRoleMappingRepository.existsByEmailAndClientId(mapping.email, clientId)) {
                throw IllegalArgumentException("User ${mapping.email} already has a role mapping for this client")
            }

            // Validate role exists
            val role = roleTypeRepository.findByRoleCode(mapping.roleCode).orElse(null)
                ?: throw IllegalArgumentException("Role ${mapping.roleCode} not found")

            // Ensure client ID matches context
            val mappingWithClientId = mapping.copy(
                clientId = clientId,
                permissions = role.defaultPermissions // Inherit default permissions from role
            )

            val savedMapping = userRoleMappingRepository.save(mappingWithClientId)
            logger.info("Successfully created user role mapping: ${savedMapping.userRoleCode} for client ID: $clientId")
            savedMapping
        }
    }

    /**
     * Gets a user role mapping by code
     */
    fun getUserRoleMappingByCode(userRoleCode: String): UserRoleMapping? {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching user role mapping: $userRoleCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
        }
    }

    /**
     * Gets a user role mapping by email
     */
    fun getUserRoleMappingByEmail(email: String): UserRoleMapping? {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching user role mapping for email: $email and client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            userRoleMappingRepository.findByEmailAndClientId(email.lowercase(), clientId).orElse(null)
        }
    }

    /**
     * Updates a user role mapping
     */
    @Transactional
    fun updateUserRoleMapping(userRoleCode: String, updates: Map<String, Any>): UserRoleMapping {
        val clientId = requireCurrentClientId()
        logger.info("Updating user role mapping: $userRoleCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            val existingMapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            // Ensure mapping belongs to current tenant
            if (existingMapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            var updatedMapping = existingMapping

            // Update role if provided
            updates["roleCode"]?.let { roleCode ->
                val newRole = roleTypeRepository.findByRoleCode(roleCode as String).orElse(null)
                    ?: throw IllegalArgumentException("Role $roleCode not found")

                updatedMapping = updatedMapping.copy(
                    roleCode = newRole.roleCode,
                    permissions = newRole.defaultPermissions // Update permissions from new role
                )
            }

            // Update warehouses
            updates["warehouses"]?.let { warehouses ->
                if (warehouses is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMapping = updatedMapping.copy(warehouses = warehouses as List<String>)
                }
            }

            // Update current warehouse
            updates["currentWarehouse"]?.let { warehouse ->
                updatedMapping = updatedMapping.changeWarehouse(warehouse as String)
            }

            // Update custom permissions
            updates["customPermissions"]?.let { permissions ->
                if (permissions is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    updatedMapping = updatedMapping.copy(customPermissions = permissions as Map<String, Boolean>)
                }
            }

            // Update active status
            updates["isActive"]?.let { isActive ->
                updatedMapping = updatedMapping.copy(isActive = isActive as Boolean)
            }

            val savedMapping = userRoleMappingRepository.save(updatedMapping)
            logger.info("Successfully updated user role mapping: $userRoleCode for client ID: $clientId")
            savedMapping
        }
    }

    /**
     * Deletes a user role mapping
     */
    @Transactional
    fun deleteUserRoleMapping(userRoleCode: String) {
        val clientId = requireCurrentClientId()
        logger.info("Deleting user role mapping: $userRoleCode for client ID: $clientId")

        multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            if (mapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            userRoleMappingRepository.deleteByUserRoleCode(userRoleCode)
            logger.info("Successfully deleted user role mapping: $userRoleCode for client ID: $clientId")
        }
    }

    /**
     * Gets all user role mappings for current tenant
     */
    fun getAllUserRoleMappings(): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching all user role mappings for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            userRoleMappingRepository.findByClientId(clientId)
        }
    }

    /**
     * Gets active user role mappings for current tenant
     */
    fun getActiveUserRoleMappings(): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching active user role mappings for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            userRoleMappingRepository.findByClientIdAndIsActive(clientId, true)
        }
    }

    /**
     * Gets user role mappings by role
     */
    fun getUsersByRole(roleCode: String): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching users with role $roleCode for client ID: $clientId")

        return multiTenantMongoTemplate.withCurrentTenant {
            userRoleMappingRepository.findByClientIdAndRoleCode(clientId, roleCode)
        }
    }

    /**
     * Gets user role mappings by warehouse
     */
    fun getUsersByWarehouse(warehouseId: String): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Fetching users for warehouse $warehouseId")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .and("warehouses").`is`(warehouseId)
            )
            mongoTemplate.find(query, UserRoleMapping::class.java)
        }
    }

    /**
     * Updates user's last login
     */
    @Transactional
    fun updateLastLogin(email: String): UserRoleMapping? {
        val clientId = requireCurrentClientId()
        logger.debug("Updating last login for user $email")

        return multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByEmailAndClientId(email.lowercase(), clientId).orElse(null)
            if (mapping != null) {
                val updated = mapping.updateLastLogin()
                userRoleMappingRepository.save(updated)
            } else {
                null
            }
        }
    }

    /**
     * Adds warehouse to user
     */
    @Transactional
    fun addWarehouseToUser(userRoleCode: String, warehouseId: String): UserRoleMapping {
        val clientId = requireCurrentClientId()
        logger.info("Adding warehouse $warehouseId to user $userRoleCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            if (mapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            val updated = mapping.addWarehouse(warehouseId)
            userRoleMappingRepository.save(updated)
        }
    }

    /**
     * Removes warehouse from user
     */
    @Transactional
    fun removeWarehouseFromUser(userRoleCode: String, warehouseId: String): UserRoleMapping {
        val clientId = requireCurrentClientId()
        logger.info("Removing warehouse $warehouseId from user $userRoleCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            if (mapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            val updated = mapping.removeWarehouse(warehouseId)
            userRoleMappingRepository.save(updated)
        }
    }

    /**
     * Applies custom permission to user
     */
    @Transactional
    fun applyCustomPermission(userRoleCode: String, permissionKey: String, value: Boolean): UserRoleMapping {
        val clientId = requireCurrentClientId()
        logger.info("Applying custom permission $permissionKey=$value to user $userRoleCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            if (mapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            val updated = mapping.applyCustomPermission(permissionKey, value)
            userRoleMappingRepository.save(updated)
        }
    }

    /**
     * Gets effective permissions for a user
     */
    fun getEffectivePermissions(userRoleCode: String): PermissionsSchema {
        val clientId = requireCurrentClientId()
        logger.debug("Getting effective permissions for user $userRoleCode")

        return multiTenantMongoTemplate.withCurrentTenant {
            val mapping = userRoleMappingRepository.findByUserRoleCode(userRoleCode).orElse(null)
                ?: throw IllegalArgumentException("User role mapping with code $userRoleCode not found")

            if (mapping.clientId != clientId) {
                throw IllegalArgumentException("User role mapping does not belong to current tenant")
            }

            mapping.getEffectivePermissions()
        }
    }

    /**
     * Finds users with specific permission
     */
    fun findUsersWithPermission(permissionName: String, value: Boolean): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding users with permission $permissionName=$value")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .and("permissions.$permissionName").`is`(value)
            )
            mongoTemplate.find(query, UserRoleMapping::class.java)
        }
    }

    /**
     * Generates next user role code
     */
    fun generateNextUserRoleCode(): String {
        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val lastMapping = userRoleMappingRepository.findTopByOrderByUserRoleCodeDesc().orElse(null)
            if (lastMapping != null) {
                val lastNumber = lastMapping.userRoleCode.substring(3).toInt()
                UserRoleMapping.generateUserRoleCode(lastNumber + 1)
            } else {
                UserRoleMapping.generateUserRoleCode(1)
            }
        }
    }

    /**
     * Finds inactive users (haven't logged in for specified days)
     */
    fun findInactiveUsers(daysInactive: Int): List<UserRoleMapping> {
        val clientId = requireCurrentClientId()
        logger.debug("Finding users inactive for $daysInactive days")

        return multiTenantMongoTemplate.withCurrentTenant { mongoTemplate ->
            val cutoffDate = LocalDateTime.now().minusDays(daysInactive.toLong())
            val query = Query(
                Criteria.where("clientId").`is`(clientId)
                    .orOperator(
                        Criteria.where("lastLogin").lt(cutoffDate),
                        Criteria.where("lastLogin").exists(false)
                    )
            )
            mongoTemplate.find(query, UserRoleMapping::class.java)
        }
    }
}