package com.wmspro.tenant.config

import com.mongodb.ClientSessionOptions
import com.mongodb.client.MongoDatabase
import com.wmspro.common.mongo.MongoConnectionStorage
import org.slf4j.LoggerFactory
import org.springframework.dao.support.PersistenceExceptionTranslator
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.core.MongoExceptionTranslator

/**
 * Tenant-aware MongoDB Database Factory for Tenant Service
 * This factory is a singleton bean but internally reads the connection from ThreadLocal
 * for each database access.
 *
 * IMPORTANT: Tenant Service uses CENTRAL database by default (central = true)
 * but the interceptor may set tenant-specific connections in ThreadLocal for certain paths.
 */
class TenantAwareMongoDatabaseFactory(
    private val defaultConnectionUri: String,
    private val defaultDatabase: String
) : MongoDatabaseFactory {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val exceptionTranslator = MongoExceptionTranslator()

    /**
     * Returns the MongoDatabase for the current request context.
     * Reads connection from ThreadLocal storage which may be:
     * - Central database connection (for /api/v1/tenants paths)
     * - Tenant-specific connection (for other paths)
     */
    override fun getMongoDatabase(): MongoDatabase {
        val connectionUri = try {
            // Get connection from ThreadLocal (set by TenantInterceptor)
            // The interceptor decides whether to use central or tenant-specific DB
            val uri = MongoConnectionStorage.getConnection(central = true)
            logger.debug("Getting database for connection from ThreadLocal")
            uri
        } catch (e: Exception) {
            logger.warn("No connection in ThreadLocal, using default central connection. This should only happen during startup or health checks.")
            defaultConnectionUri
        }

        // Create a factory for the specific connection and get the database
        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.mongoDatabase
    }

    /**
     * Returns the MongoDatabase with a specific database name override.
     */
    override fun getMongoDatabase(dbName: String): MongoDatabase {
        val connectionUri = try {
            MongoConnectionStorage.getConnection(central = true)
        } catch (e: Exception) {
            logger.warn("No connection in ThreadLocal for database: $dbName")
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, dbName)
        return factory.mongoDatabase
    }

    /**
     * Session support - delegates to a factory instance
     */
    override fun getSession(options: ClientSessionOptions): com.mongodb.client.ClientSession {
        val connectionUri = try {
            MongoConnectionStorage.getConnection(central = true)
        } catch (e: Exception) {
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.getSession(options)
    }

    /**
     * Starts a session with the current connection
     */
    override fun withSession(session: com.mongodb.client.ClientSession): MongoDatabaseFactory {
        val connectionUri = try {
            MongoConnectionStorage.getConnection(central = true)
        } catch (e: Exception) {
            defaultConnectionUri
        }

        val factory = DatabaseConfiguration(connectionUri, defaultDatabase)
        return factory.withSession(session)
    }

    /**
     * Returns whether this factory is associated with a session
     */
    override fun isTransactionActive(): Boolean {
        return false
    }

    /**
     * Returns the exception translator for MongoDB exceptions
     */
    override fun getExceptionTranslator(): PersistenceExceptionTranslator {
        return exceptionTranslator
    }
}
