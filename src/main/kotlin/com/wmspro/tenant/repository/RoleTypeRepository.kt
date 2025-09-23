package com.wmspro.tenant.repository

import com.wmspro.tenant.model.RoleType
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

/**
 * Repository for RoleType model
 * This repository operates on tenant-specific databases
 */
@Repository
interface RoleTypeRepository : MongoRepository<RoleType, String>, CustomRoleTypeRepository {

    /**
     * Finds a role by role code
     */
    fun findByRoleCode(roleCode: String): Optional<RoleType>

    /**
     * Finds a role by role name
     */
    fun findByRoleName(roleName: String): Optional<RoleType>

    /**
     * Finds all system roles
     */
    fun findByIsSystemRole(isSystemRole: Boolean): List<RoleType>

    /**
     * Finds roles by hierarchy level
     */
    fun findByHierarchyLevel(level: Int): List<RoleType>

    /**
     * Finds roles with hierarchy level less than or equal to specified level
     */
    fun findByHierarchyLevelLessThanEqual(level: Int): List<RoleType>

    /**
     * Checks if a role exists by role name
     */
    fun existsByRoleName(roleName: String): Boolean

    /**
     * Checks if a role exists by role code
     */
    fun existsByRoleCode(roleCode: String): Boolean

    /**
     * Deletes a role by role code
     */
    fun deleteByRoleCode(roleCode: String): Long

    /**
     * Finds roles that have a specific mobile feature
     */
    @Query("{'mobileFeatures': ?0}")
    fun findByMobileFeature(feature: String): List<RoleType>

    /**
     * Finds roles that have access to a specific menu
     */
    @Query("{'menuAccess': ?0}")
    fun findByMenuAccess(menu: String): List<RoleType>

    /**
     * Finds the next available role code number
     */
    @Query(value = "{}", fields = "{'roleCode': 1}", sort = "{'roleCode': -1}")
    fun findTopByOrderByRoleCodeDesc(): Optional<RoleType>
}

/**
 * Custom repository interface for complex RoleType operations
 */
interface CustomRoleTypeRepository {
    /**
     * Generates the next role code
     */
    fun generateNextRoleCode(): String

    /**
     * Finds roles with specific permissions
     */
    fun findRolesWithPermission(permissionName: String, value: Boolean): List<RoleType>
}