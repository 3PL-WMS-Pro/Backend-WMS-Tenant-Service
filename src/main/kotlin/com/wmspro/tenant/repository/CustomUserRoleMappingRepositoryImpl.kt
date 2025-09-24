package com.wmspro.tenant.repository

import com.wmspro.tenant.model.UserRoleMapping
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

/**
 * Custom implementation for UserRoleMapping repository methods
 * Spring Data MongoDB will automatically combine this with the interface
 */
@Repository
class CustomUserRoleMappingRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : CustomUserRoleMappingRepository {

    override fun generateNextUserRoleCode(): String {
        val query = Query()
        query.with(Sort.by(Sort.Direction.DESC, "roleCode"))
        query.limit(1)

        val lastMapping = mongoTemplate.findOne(query, UserRoleMapping::class.java)

        return if (lastMapping != null && lastMapping.roleCode.startsWith("USR")) {
            val lastNumber = lastMapping.roleCode.substring(3).toIntOrNull() ?: 0
            "USR${(lastNumber + 1).toString().padStart(6, '0')}"
        } else {
            "USR000001"
        }
    }

    override fun findUsersWithPermission(clientId: Int, permissionName: String, value: Boolean): List<UserRoleMapping> {
        val query = Query()
        query.addCriteria(Criteria.where("clientId").`is`(clientId))
        query.addCriteria(Criteria.where("permissions.$permissionName").`is`(value))
        query.addCriteria(Criteria.where("isActive").`is`(true))

        return mongoTemplate.find(query, UserRoleMapping::class.java)
    }

    override fun updateLastLogin(userRoleCode: String): Boolean {
        val query = Query(Criteria.where("roleCode").`is`(userRoleCode))
        val update = Update()
        update.set("lastLogin", LocalDateTime.now())

        val result = mongoTemplate.updateFirst(query, update, UserRoleMapping::class.java)
        return result.modifiedCount > 0
    }

    override fun bulkUpdateWarehouseAssignments(userRoleCodes: List<String>, warehouseIds: List<String>): Int {
        val query = Query(Criteria.where("roleCode").`in`(userRoleCodes))
        val update = Update()
        update.set("warehouseAssignments", warehouseIds)
        update.set("updatedAt", LocalDateTime.now())

        val result = mongoTemplate.updateMulti(query, update, UserRoleMapping::class.java)
        return result.modifiedCount.toInt()
    }

    override fun findInactiveUsers(clientId: Int, daysInactive: Int): List<UserRoleMapping> {
        val cutoffDate = LocalDateTime.now().minusDays(daysInactive.toLong())

        val query = Query()
        query.addCriteria(Criteria.where("clientId").`is`(clientId))
        query.addCriteria(
            Criteria().orOperator(
                Criteria.where("lastLogin").lt(cutoffDate),
                Criteria.where("lastLogin").`is`(null)
            )
        )
        query.addCriteria(Criteria.where("isActive").`is`(true))

        return mongoTemplate.find(query, UserRoleMapping::class.java)
    }

    override fun updateCustomPermissions(userRoleCode: String, permissions: Map<String, Boolean>): Boolean {
        val query = Query(Criteria.where("roleCode").`is`(userRoleCode))
        val update = Update()

        // Update each permission individually
        permissions.forEach { (key, value) ->
            update.set("customPermissions.$key", value)
        }
        update.set("updatedAt", LocalDateTime.now())

        val result = mongoTemplate.updateFirst(query, update, UserRoleMapping::class.java)
        return result.modifiedCount > 0
    }
}