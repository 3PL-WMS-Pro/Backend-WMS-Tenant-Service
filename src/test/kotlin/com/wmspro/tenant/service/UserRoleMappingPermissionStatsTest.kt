package com.wmspro.tenant.service

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.repository.UserRoleMappingRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.mongodb.core.MongoTemplate

class UserRoleMappingPermissionStatsTest {
    @Test
    fun `billing permissions appear in granted and management totals`() {
        val users = Mockito.mock(UserRoleMappingRepository::class.java)
        val service = UserRoleMappingService(users, Mockito.mock(MongoTemplate::class.java))
        Mockito.`when`(users.findByEmail("manager@example.com")).thenReturn(listOf(
            UserRoleMapping(userRoleCode = "UR-005", email = "manager@example.com", clientId = 199,
                roleCode = "ROLE-002", permissions = PermissionsSchema(
                    canViewWarehouseJobs = true, canManageSupplierExpenses = true, canAccessApi = true))
        ))
        val detail = requireNotNull(service.getUserRoleMappingDetail("manager@example.com", 199))
        assertTrue(detail.permissions.canManageSupplierExpenses)
        assertTrue(detail.permissions.canViewWarehouseJobs)
        assertEquals(3, detail.permissionStats.grantedCount)
        assertEquals(2, detail.permissionStats.managementCount)
        assertEquals(0, detail.permissionStats.operationalCount)
    }
}
