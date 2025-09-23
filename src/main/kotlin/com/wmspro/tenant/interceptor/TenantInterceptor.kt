package com.wmspro.tenant.interceptor

import com.wmspro.common.constants.GlobalConstants
import com.wmspro.common.interceptor.TenantContext
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.utils.TenantConnectionFetcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Tenant interceptor for Tenant Service
 * Handles tenant extraction and MongoDB connection switching
 * Uses common TenantConnectionFetcher to get database connections
 */
@Component
class TenantInterceptor(
    private val tenantConnectionFetcher: TenantConnectionFetcher
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(TenantInterceptor::class.java)

    companion object {
        // Paths that should use central database (no tenant context needed)
        // These paths include /api/v1/tenants which covers /api/v1/tenants/internal
        private val CENTRAL_DB_PATHS = listOf(
            "/api/v1/tenants",  // All tenant management operations use central DB (includes internal)
            "/api/v1/tenant-settings", // Tenant settings also use central DB
            "/actuator",
            "/health",
            "/info",
            "/swagger-ui",
            "/v3/api-docs",
            "/error"
        )
    }

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any
    ): Boolean {
        val requestUrl = request.requestURI
        logger.debug("Processing request: $requestUrl")

        // Store auth token if present (like LTR does)
        request.getHeader("Authorization")?.let {
            // Could store in AuthTokenStorage if needed
        }

        // Check if this path should use central database
        if (CENTRAL_DB_PATHS.any { requestUrl.startsWith(it) }) {
            logger.debug("Using central database for path: $requestUrl")
            MongoConnectionStorage.setConnection(MongoConnectionStorage.DEFAULT_DB_URL_CENTRAL)
            return true
        }

        // Extract tenant header
        val tenantHeader = request.getHeader(GlobalConstants.TENANT_HEADER)
            ?: request.getHeader(GlobalConstants.CLIENT_HEADER)
            ?: request.getParameter(GlobalConstants.TENANT_QUERY_PARAM)

        if (tenantHeader.isNullOrBlank()) {
            logger.warn("No tenant header found for path: $requestUrl")
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant context required")
            return false
        }

        try {
            val tenantId = tenantHeader.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid tenant ID format: $tenantHeader")

            // Set tenant context for other components that might need it
            TenantContext.setCurrentTenant(tenantHeader)

            // Use common utility to fetch connection (handles caching internally)
            val dbConnectionString = tenantConnectionFetcher.fetchTenantConnection(tenantId)

            if (dbConnectionString == null) {
                logger.error("No database connection found for tenant: $tenantId")
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant not found")
                return false
            }

            // Set the connection in ThreadLocal
            MongoConnectionStorage.setConnection(dbConnectionString)
            return true

        } catch (e: Exception) {
            logger.error("Error setting tenant database connection", e)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to configure tenant database")
            return false
        }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        // Clear connections exactly like LTR
        MongoConnectionStorage.clear()
        TenantContext.clear()
        logger.debug("Cleared connections for request: ${request.requestURI}")
    }
}