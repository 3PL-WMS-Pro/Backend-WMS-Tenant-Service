package com.wmspro.tenant.repository

import com.wmspro.tenant.model.UserRoleMapping
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for UserRoleMapping model
 * This repository operates on tenant-specific databases
 */
@Repository
interface UserRoleMappingRepository : MongoRepository<UserRoleMapping, String>, CustomUserRoleMappingRepository {

    /**
     * Finds a user role mapping by user role code
     */
    fun findByUserRoleCode(userRoleCode: String): Optional<UserRoleMapping>

    /**
     * Finds a user role mapping by email and client ID
     */
    fun findByEmailAndClientId(email: String, clientId: Int): Optional<UserRoleMapping>

    /**
     * Finds all user role mappings for a client
     */
    fun findByClientId(clientId: Int): List<UserRoleMapping>

    /**
     * Finds all user role mappings for an email
     */
    fun findByEmail(email: String): List<UserRoleMapping>

    /**
     * Finds all user role mappings for a role
     */
    fun findByRoleCode(roleCode: String): List<UserRoleMapping>

    /**
     * Finds active user role mappings for a client
     */
    fun findByClientIdAndIsActive(clientId: Int, isActive: Boolean): List<UserRoleMapping>

    /**
     * Finds user role mappings by warehouse
     */
    @Query("{'warehouses': ?0}")
    fun findByWarehouse(warehouseId: String): List<UserRoleMapping>

    /**
     * Finds user role mappings by current warehouse
     */
    fun findByCurrentWarehouse(warehouseId: String): List<UserRoleMapping>

    /**
     * Finds user role mappings for a client and role
     */
    fun findByClientIdAndRoleCode(clientId: Int, roleCode: String): List<UserRoleMapping>

    /**
     * Checks if a user role mapping exists
     */
    fun existsByEmailAndClientId(email: String, clientId: Int): Boolean

    /**
     * Checks if a user role mapping exists by code
     */
    fun existsByUserRoleCode(userRoleCode: String): Boolean

    /**
     * Deletes a user role mapping by code
     */
    fun deleteByUserRoleCode(userRoleCode: String): Long

    /**
     * Deletes all user role mappings for an email and client
     */
    fun deleteByEmailAndClientId(email: String, clientId: Int): Long

    /**
     * Counts active users for a client
     */
    fun countByClientIdAndIsActive(clientId: Int, isActive: Boolean): Long

    /**
     * Counts users by role for a client
     */
    fun countByClientIdAndRoleCode(clientId: Int, roleCode: String): Long

    /**
     * Finds user role mappings created by a specific user
     */
    fun findByCreatedBy(createdBy: String): List<UserRoleMapping>

    /**
     * Finds the next available user role code
     */
    @Query(value = "{}", fields = "{'userRoleCode': 1}", sort = "{'userRoleCode': -1}")
    fun findTopByOrderByUserRoleCodeDesc(): Optional<UserRoleMapping>
}

/**
 * Custom repository interface for complex UserRoleMapping operations
 */
interface CustomUserRoleMappingRepository {
    /**
     * Generates the next user role code
     */
    fun generateNextUserRoleCode(): String

    /**
     * Finds users with a specific permission
     */
    fun findUsersWithPermission(clientId: Int, permissionName: String, value: Boolean): List<UserRoleMapping>

    /**
     * Updates user's last login timestamp
     */
    fun updateLastLogin(userRoleCode: String): Boolean

    /**
     * Bulk updates warehouse assignments for multiple users
     */
    fun bulkUpdateWarehouseAssignments(userRoleCodes: List<String>, warehouseIds: List<String>): Int

    /**
     * Finds users who haven't logged in for specified days
     */
    fun findInactiveUsers(clientId: Int, daysInactive: Int): List<UserRoleMapping>

    /**
     * Updates custom permissions for a user
     */
    fun updateCustomPermissions(userRoleCode: String, permissions: Map<String, Boolean>): Boolean
}