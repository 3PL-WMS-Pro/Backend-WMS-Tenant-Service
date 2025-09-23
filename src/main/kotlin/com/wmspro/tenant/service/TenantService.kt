package com.wmspro.tenant.service

import com.wmspro.tenant.dto.*
import com.wmspro.tenant.model.TenantDatabaseMapping
import com.wmspro.tenant.model.TenantStatus
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * Service for managing tenant database mappings
 * This service operates on the central database only
 * Interceptor ensures central DB connection for /api/v1/tenants paths
 */
@Service
class TenantService(
    private val tenantRepository: TenantDatabaseMappingRepository,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(TenantService::class.java)

    /**
     * Creates a new tenant database mapping
     */
    @Transactional
    fun createTenant(mapping: TenantDatabaseMapping): TenantDatabaseMapping {
        logger.info("Creating new tenant database mapping for client ID: ${mapping.clientId}")

        // Check if tenant already exists
        if (tenantRepository.existsByClientId(mapping.clientId)) {
            throw IllegalArgumentException("Tenant with client ID ${mapping.clientId} already exists")
        }

        // Validate MongoDB connection before saving
        validateMongoConnection(mapping)

        val savedMapping = tenantRepository.save(mapping)
        logger.info("Successfully created tenant database mapping for client ID: ${mapping.clientId}")

        return savedMapping
    }

    /**
     * Gets a tenant database mapping by client ID
     */
    @Cacheable(value = ["tenantMappings"], key = "#clientId")
    fun getTenantByClientId(clientId: Int): TenantDatabaseMapping? {
        logger.debug("Fetching tenant database mapping for client ID: $clientId")
        return tenantRepository.findByClientId(clientId).orElse(null)
    }

    /**
     * Updates a tenant database mapping
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun updateTenant(clientId: Int, updates: Map<String, Any>): TenantDatabaseMapping {
        logger.info("Updating tenant database mapping for client ID: $clientId")

        val existingMapping = getTenantByClientId(clientId)
            ?: throw IllegalArgumentException("Tenant with client ID $clientId not found")

        // Apply updates (simplified for brevity - in production, use proper DTO)
        var updatedMapping = existingMapping

        updates["status"]?.let { status ->
            if (status is String) {
                updatedMapping = updatedMapping.copy(status = TenantStatus.valueOf(status))
            }
        }

        updates["tenantSettings"]?.let { settings ->
            if (settings is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                updatedMapping = updatedMapping.copy(
                    tenantSettings = mergeTenantSettings(
                        existingMapping.tenantSettings,
                        settings as Map<String, Any>
                    )
                )
            }
        }

        updatedMapping = updatedMapping.copy(lastConnected = LocalDateTime.now())

        val savedMapping = tenantRepository.save(updatedMapping)
        logger.info("Successfully updated tenant database mapping for client ID: $clientId")

        return savedMapping
    }

    /**
     * Deletes a tenant database mapping
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun deleteTenant(clientId: Int) {
        logger.info("Deleting tenant database mapping for client ID: $clientId")

        if (!tenantRepository.existsByClientId(clientId)) {
            throw IllegalArgumentException("Tenant with client ID $clientId not found")
        }

        // Clear cached connection for this tenant
        com.wmspro.common.mongo.MongoConnectionStorage.removeCachedConnection(clientId.toString())

        tenantRepository.deleteByClientId(clientId)
        logger.info("Successfully deleted tenant database mapping for client ID: $clientId")
    }

    /**
     * Gets all tenants
     */
    fun getAllTenants(): List<TenantDatabaseMapping> {
        logger.debug("Fetching all tenant database mappings")
        return tenantRepository.findAll()
    }

    /**
     * Gets active tenants
     */
    fun getActiveTenants(): List<TenantDatabaseMapping> {
        logger.debug("Fetching active tenant database mappings")
        return tenantRepository.findByStatus(TenantStatus.ACTIVE)
    }

    /**
     * Updates tenant connection status
     */
    @Transactional
    @CacheEvict(value = ["tenantMappings"], key = "#clientId")
    fun updateConnectionStatus(clientId: Int, connected: Boolean) {
        val tenant = getTenantByClientId(clientId)
            ?: throw IllegalArgumentException("Tenant with client ID $clientId not found")

        val updatedTenant = if (connected) {
            tenant.copy(lastConnected = LocalDateTime.now())
        } else {
            tenant
        }

        tenantRepository.save(updatedTenant)
    }

    /**
     * Validates MongoDB connection for a tenant
     */
    private fun validateMongoConnection(mapping: TenantDatabaseMapping) {
        try {
            // Build connection string and test it
            val connectionString = "${mapping.mongoConnection.url}/${mapping.mongoConnection.databaseName}"
            val connectionTest = com.mongodb.ConnectionString(connectionString)

            // Try to create a client and test connection
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connectionTest)
                .build()

            val client = com.mongodb.client.MongoClients.create(settings)
            client.listDatabaseNames().first() // This will trigger connection test
            client.close()

            logger.info("Successfully validated MongoDB connection for client ID: ${mapping.clientId}")
        } catch (e: Exception) {
            logger.error("Failed to validate MongoDB connection for client ID: ${mapping.clientId}", e)
            throw IllegalArgumentException("Invalid MongoDB connection configuration", e)
        }
    }

    /**
     * Merges tenant settings
     */
    private fun mergeTenantSettings(
        existing: com.wmspro.tenant.model.TenantSettings,
        updates: Map<String, Any>
    ): com.wmspro.tenant.model.TenantSettings {
        // This is a simplified merge - in production, implement deep merge
        return existing.copy(
            billingSettings = updates["billingSettings"] as? Map<String, Any> ?: existing.billingSettings,
            inventorySettings = updates["inventorySettings"] as? Map<String, Any> ?: existing.inventorySettings,
            orderProcessingSettings = updates["orderProcessingSettings"] as? Map<String, Any> ?: existing.orderProcessingSettings,
            warehouseOperations = updates["warehouseOperations"] as? Map<String, Any> ?: existing.warehouseOperations,
            integrationSettings = updates["integrationSettings"] as? Map<String, Any> ?: existing.integrationSettings,
            securitySettings = updates["securitySettings"] as? Map<String, Any> ?: existing.securitySettings,
            notificationPreferences = updates["notificationPreferences"] as? Map<String, Any> ?: existing.notificationPreferences
        )
    }

    /**
     * Gets tenant by database name
     */
    fun getTenantsByDatabaseName(databaseName: String): List<TenantDatabaseMapping> {
        return tenantRepository.findByDatabaseName(databaseName)
    }

    /**
     * Checks if a tenant exists
     */
    fun tenantExists(clientId: Int): Boolean {
        return tenantRepository.existsByClientId(clientId)
    }

    /**
     * Counts tenants by status
     */
    fun countTenantsByStatus(status: TenantStatus): Long {
        return tenantRepository.countByStatus(status)
    }

    /**
     * Creates a new tenant with validation (API 066)
     */
    @Transactional
    fun createTenantWithValidation(request: CreateTenantRequest): TenantDatabaseMapping {
        logger.info("Creating new tenant with validation for client ID: ${request.clientId}")

        // Check if tenant already exists
        if (tenantRepository.existsByClientId(request.clientId)) {
            throw DuplicateKeyException("Tenant with client ID ${request.clientId} already exists")
        }

        // Check if database name already exists
        if (tenantRepository.findByDatabaseName(request.mongoConnection.databaseName).isNotEmpty()) {
            throw DuplicateKeyException("Database name ${request.mongoConnection.databaseName} already exists")
        }

        // Create tenant mapping
        val mapping = TenantDatabaseMapping(
            clientId = request.clientId,
            mongoConnection = request.mongoConnection,
            s3Configuration = request.s3Configuration,
            tenantSettings = request.tenantSettings,
            status = TenantStatus.ACTIVE,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )

        // Test MongoDB connection
        try {
            validateMongoConnection(mapping)
        } catch (e: Exception) {
            throw ConnectionTestException("MongoDB connection test failed: ${e.message}")
        }

        // Test S3 connection (simplified - in production would test actual S3 access)
        if (request.s3Configuration.bucketName.isBlank() ||
            request.s3Configuration.accessKey.isBlank() ||
            request.s3Configuration.secretKey.isBlank()) {
            throw ConnectionTestException("Invalid S3 configuration")
        }

        // Save and return
        val saved = tenantRepository.save(mapping)

        // Initialize collections in tenant database
        initializeTenantDatabase(saved)

        return saved
    }

    /**
     * Gets tenant by ObjectId (API 073)
     */
    fun getTenantById(tenantId: String): TenantDatabaseMapping? {
        logger.debug("Fetching tenant by ID: $tenantId")

        return if (ObjectId.isValid(tenantId)) {
            tenantRepository.findById(tenantId).orElse(null)
        } else {
            null
        }
    }

    /**
     * Checks connection health for a tenant
     */
    fun checkConnectionHealth(clientId: Int): String {
        return try {
            val tenant = getTenantByClientId(clientId) ?: return "NOT_FOUND"

            // Test MongoDB connection
            val connectionString = "${tenant.mongoConnection.url}/${tenant.mongoConnection.databaseName}"
            val connectionTest = com.mongodb.ConnectionString(connectionString)
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connectionTest)
                .build()

            val client = com.mongodb.client.MongoClients.create(settings)
            client.listDatabaseNames().first()
            client.close()

            "HEALTHY"
        } catch (e: Exception) {
            logger.warn("Connection health check failed for tenant $clientId: ${e.message}")
            "UNHEALTHY"
        }
    }

    /**
     * Calculates usage statistics for a tenant (API 073)
     */
    fun calculateUsageStats(clientId: Int): UsageStats {
        // In production, this would query actual database metrics
        // For now, return mock data
        return UsageStats(
            databaseSizeMb = 150.5,
            totalDocuments = 12500,
            activeUsersCount = 25,
            storageUsedGb = 5.2
        )
    }

    /**
     * Initializes collections and indexes in tenant database
     */
    private fun initializeTenantDatabase(tenant: TenantDatabaseMapping) {
        logger.info("Initializing database for tenant ${tenant.clientId}")

        try {
            // Create MongoDB client for tenant database
            val connectionString = "${tenant.mongoConnection.url}/${tenant.mongoConnection.databaseName}"
            val connectionTest = com.mongodb.ConnectionString(connectionString)
            val settings = com.mongodb.MongoClientSettings.builder()
                .applyConnectionString(connectionTest)
                .build()

            val client = com.mongodb.client.MongoClients.create(settings)
            val database = client.getDatabase(tenant.mongoConnection.databaseName)

            // Create collections
            val collections = listOf(
                "user_role_mappings",
                "warehouses",
                "skus",
                "storage_items",
                "tasks",
                "asns",
                "grns",
                "orders"
            )

            collections.forEach { collectionName ->
                if (!database.listCollectionNames().any { it == collectionName }) {
                    database.createCollection(collectionName)
                    logger.debug("Created collection $collectionName for tenant ${tenant.clientId}")
                }
            }

            // Create indexes (simplified - in production would create specific indexes)
            val userRoleCollection = database.getCollection("user_role_mappings")
            userRoleCollection.createIndex(com.mongodb.client.model.Indexes.ascending("email"))
            userRoleCollection.createIndex(com.mongodb.client.model.Indexes.ascending("client_id"))

            client.close()
            logger.info("Successfully initialized database for tenant ${tenant.clientId}")
        } catch (e: Exception) {
            logger.error("Failed to initialize database for tenant ${tenant.clientId}", e)
            // Don't fail tenant creation if initialization fails - can be done later
        }
    }
}