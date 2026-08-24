package com.wmspro.tenant.billing.warehousejob

import com.wmspro.common.schema.PermissionsSchema
import com.wmspro.tenant.billing.warehousejob.api.WarehouseJobStaffAuthorization
import com.wmspro.tenant.model.UserRoleMapping
import com.wmspro.tenant.repository.UserRoleMappingRepository
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.server.ResponseStatusException

class WarehouseJobStaffAuthorizationTest {
    private val users = Mockito.mock(UserRoleMappingRepository::class.java)
    private val authorization = WarehouseJobStaffAuthorization(users)

    @Test
    fun `existing authenticated admins do not require a permission backfill`() {
        val request = request("admin@example.com", "ADMIN")

        assertDoesNotThrow { authorization.require(request, "canSyncWarehouseJobs") }
        Mockito.verifyNoInteractions(users)
    }

    @Test
    fun `non-admin users still require the requested effective permission`() {
        val request = request("operator@example.com", "OPERATOR")
        Mockito.`when`(users.findByEmail("operator@example.com")).thenReturn(
            listOf(
                UserRoleMapping(
                    userRoleCode = "UR-001",
                    email = "operator@example.com",
                    permissions = PermissionsSchema(canViewWarehouseJobs = true)
                )
            )
        )

        assertDoesNotThrow { authorization.require(request, "canViewWarehouseJobs") }

        val denied = assertThrows<ResponseStatusException> {
            authorization.require(request, "canSyncWarehouseJobs")
        }
        assertEquals(403, denied.statusCode.value())
    }

    private fun request(email: String, userType: String): HttpServletRequest =
        Mockito.mock(HttpServletRequest::class.java).also {
            Mockito.`when`(it.getHeader("X-User-Email")).thenReturn(email)
            Mockito.`when`(it.getHeader("X-User-Type")).thenReturn(userType)
        }
}
