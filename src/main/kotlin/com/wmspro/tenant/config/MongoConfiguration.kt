package com.wmspro.tenant.config

import com.mongodb.ConnectionString
import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

/**
 * MongoDB configuration following LTR-Backend pattern exactly
 * Simple and clean with single lazy MongoTemplate
 */
@Configuration
@EnableMongoRepositories(basePackages = ["com.wmspro.tenant.repository"])
class MongoConfiguration(
    @Value("\${spring.data.mongodb.uri:mongodb://localhost:27017/wms_central}")
    private val centralMongoUri: String
) {

    init {
        // Initialize the default MongoDB URLs in commons
        MongoConnectionStorage.DEFAULT_DB_URL_CENTRAL = centralMongoUri
        // For tenant service, both defaults point to central initially
        MongoConnectionStorage.DEFAULT_DB_URL = centralMongoUri
    }

    /**
     * Single lazy MongoTemplate bean - exactly like LTR-Backend
     * Gets connection from ThreadLocal storage, uses central DB as fallback
     */
    @Bean
    @Lazy
    fun mongoTemplate(): MongoTemplate {
        val connectionString = ConnectionString(MongoConnectionStorage.getConnection(central = true))
        return MongoTemplate(DatabaseConfiguration(connectionString))
    }
}