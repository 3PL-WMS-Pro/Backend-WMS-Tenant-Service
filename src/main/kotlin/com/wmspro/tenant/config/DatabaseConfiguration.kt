package com.wmspro.tenant.config

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoDatabase
import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory

/**
 * DatabaseConfiguration following LTR-Backend pattern exactly
 * Extends SimpleMongoClientDatabaseFactory to dynamically get database from ThreadLocal
 */
class DatabaseConfiguration(
    connectionUri: String,
    private val defaultDatabase: String = "wms_pro_tenants"
) : SimpleMongoClientDatabaseFactory(
    MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(connectionUri))
            .build()
    ),
    defaultDatabase
) {

    override fun doGetMongoDatabase(dbName: String): MongoDatabase {
        // Use the current ThreadLocal connection if available (tenant-specific),
        // otherwise fall back to default/central connection.
        val connectionUri = MongoConnectionStorage.getConnection(central = false)
        val connectionString = ConnectionString(connectionUri)
        val databaseName = connectionString.database ?: defaultDatabase
        return super.doGetMongoDatabase(databaseName)
    }
}