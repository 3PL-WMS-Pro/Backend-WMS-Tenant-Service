package com.wmspro.tenant.config

import com.mongodb.ConnectionString
import com.mongodb.client.MongoDatabase
import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory

/**
 * Database configuration following LTR-Backend pattern exactly
 * Dynamically switches databases based on ThreadLocal connection
 */
class DatabaseConfiguration(connectionString: ConnectionString) : SimpleMongoClientDatabaseFactory(connectionString) {

    override fun doGetMongoDatabase(dbName: String): MongoDatabase {
        val connectionString = ConnectionString(MongoConnectionStorage.getConnection())
        // Use the database name from the connection string
        return super.doGetMongoDatabase(connectionString.database ?: dbName)
    }
}