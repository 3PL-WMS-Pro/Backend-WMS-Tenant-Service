package com.wmspro.tenant.repository.impl

import com.wmspro.tenant.model.RoleType
import com.wmspro.tenant.repository.CustomRoleTypeRepository
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

/**
 * Custom implementation for complex RoleType repository operations
 */
@Repository
class CustomRoleTypeRepositoryImpl(
    private val mongoTemplate: MongoTemplate
) : CustomRoleTypeRepository {

    override fun generateNextRoleCode(): String {
        val query = Query().apply {
            fields().include("roleCode")
            limit(1)
        }
        query.with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "roleCode"))

        val lastRole = mongoTemplate.findOne(query, RoleType::class.java)
        return if (lastRole != null) {
            val lastNumber = lastRole.roleCode.substring(5).toIntOrNull() ?: 0
            RoleType.generateRoleCode(lastNumber + 1)
        } else {
            RoleType.generateRoleCode(1)
        }
    }

    override fun findRolesWithPermission(permissionName: String, value: Boolean): List<RoleType> {
        val query = Query(Criteria.where("defaultPermissions.$permissionName").`is`(value))
        return mongoTemplate.find(query, RoleType::class.java)
    }
}