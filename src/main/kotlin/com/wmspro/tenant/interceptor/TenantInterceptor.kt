package com.wmspro.tenant.interceptor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.wmspro.common.constants.GlobalConstants
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.model.TenantDatabaseMapping
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

/**
 * Tenant interceptor following LTR-Backend ClientFilter pattern exactly
 * Handles tenant extraction and MongoDB connection switching
 */
@Component
class TenantInterceptor(
    @Autowired @Lazy private val mongoTemplate: MongoTemplate
) : HandlerInterceptor {

    private val logger = LoggerFactory.getLogger(TenantInterceptor::class.java)
    private val objectMapper = jacksonObjectMapper()

    companion object {
        // Paths that should use central database (no tenant context needed)
        // Following LTR pattern - check by prefix, not specific endpoints
        private val CENTRAL_DB_PATHS = listOf(
            "/api/v1/tenants",  // All tenant management operations use central DB
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

            // Check cache first (exactly like LTR)
            val dbConnectionString = if (GlobalConstants.TENANT_DB_CONNECTIONS.containsKey(tenantId)) {
                val cached = GlobalConstants.TENANT_DB_CONNECTIONS[tenantId]!!
                logger.debug("Using cached connection for tenant: $tenantId")
                cached
            } else {
                // Fetch from database (equivalent to LTR's HTTP call to get mongo URL)
                val tenantMapping = fetchTenantMapping(tenantId)

                if (tenantMapping == null) {
                    logger.error("No database mapping found for tenant: $tenantId")
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Tenant not found")
                    return false
                }

                // Build connection string
                val connectionString = "${tenantMapping.mongoConnection.url}/${tenantMapping.mongoConnection.databaseName}"

                // Cache it
                GlobalConstants.TENANT_DB_CONNECTIONS[tenantId] = connectionString
                logger.debug("Cached connection for tenant: $tenantId")

                connectionString
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

    /**
     * Fetches tenant database mapping from central database
     * Equivalent to LTR's HTTP call to get mongo URL
     */
    private fun fetchTenantMapping(tenantId: Int): TenantDatabaseMapping? {
        return try {
            // Temporarily use central DB to fetch tenant mapping
            MongoConnectionStorage.setConnection(MongoConnectionStorage.DEFAULT_DB_URL_CENTRAL)
            mongoTemplate.findById(tenantId, TenantDatabaseMapping::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch tenant mapping for ID: $tenantId", e)
            null
        }
    }
}