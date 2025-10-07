package com.wmspro.tenant.config

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DatabaseConfiguration following LTR-Backend pattern exactly
 * Extends SimpleMongoClientDatabaseFactory to dynamically get database from ThreadLocal
 */
class DatabaseConfiguration(
    connectionUri: String,
    private val defaultDatabase: String = "wms_pro_tenants"
) : SimpleMongoClientDatabaseFactory(
    getOrCreateClient(connectionUri),
    defaultDatabase
) {
    companion object {
        private val clientCache = ConcurrentHashMap<String, MongoClient>()

        fun getOrCreateClient(connectionUri: String): MongoClient {
            return clientCache.computeIfAbsent(connectionUri) { uri ->
                createMongoClient(uri)
            }
        }

        private fun createMongoClient(connectionUri: String): MongoClient {
            val connectionString = ConnectionString(connectionUri)
            val settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToConnectionPoolSettings { builder ->
                    builder
                        .maxSize(50)
                        .minSize(5)
                        .maxWaitTime(10, TimeUnit.SECONDS)
                        .maxConnectionIdleTime(60, TimeUnit.SECONDS)
                        .maxConnectionLifeTime(30, TimeUnit.MINUTES)
                }
                .applyToSocketSettings { builder ->
                    builder
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                }
                .build()
            return MongoClients.create(settings)
        }
    }

    override fun doGetMongoDatabase(dbName: String): MongoDatabase {
        // Use the current ThreadLocal connection if available (tenant-specific),
        // otherwise fall back to default/central connection.
        val connectionUri = MongoConnectionStorage.getConnection(central = false)
        val connectionString = ConnectionString(connectionUri)
        val databaseName = connectionString.database ?: defaultDatabase

        // CRITICAL FIX: Reuse cached client instead of creating new one
        val client = getOrCreateClient(connectionUri)
        return client.getDatabase(databaseName)
    }
}