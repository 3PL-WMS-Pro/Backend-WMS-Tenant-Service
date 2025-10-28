package com.wmspro.tenant.config

import com.mongodb.ConnectionString
import com.wmspro.common.mongo.MongoConnectionStorage
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

/**
 * MongoDB configuration following LTR-Backend pattern exactly
 * Simple and clean with single lazy MongoTemplate
 */
@Configuration
class MongoConfiguration(
    @Value("\${spring.data.mongodb.uri:mongodb://flybizdigi:FlyBizDigi%40123@cloud.leadtorev.com:27170/wms_pro_tenants?authSource=admin&readPreference=primary}")
    private val centralMongoUri: String
) {

    init {
        // Initialize the default MongoDB URLs in commons
        MongoConnectionStorage.DEFAULT_DB_URL_CENTRAL = centralMongoUri
        // For tenant service, both defaults point to central initially
        MongoConnectionStorage.DEFAULT_DB_URL = centralMongoUri
    }

    /**
     * Provide MongoCustomConversions so that Java Time (JSR-310) types and other defaults are registered.
     * Passing an empty list keeps all store defaults while allowing us to obtain the appropriate SimpleTypeHolder.
     */
    @Bean
    fun mongoCustomConversions(): MongoCustomConversions = MongoCustomConversions(emptyList<Any>())

    /**
     * Properly configured MongoMappingContext using the SimpleTypeHolder from MongoCustomConversions.
     * This ensures types like java.time.LocalDateTime are treated as simple types and not introspected reflectively.
     */
    @Bean
    fun mongoMappingContext(conversions: MongoCustomConversions): MongoMappingContext {
        val context = MongoMappingContext()
        context.setSimpleTypeHolder(conversions.simpleTypeHolder)
        return context
    }

    /**
     * Tenant-aware MongoDatabaseFactory bean for Spring Data Repositories
     * This is a singleton bean, but it internally reads the connection from ThreadLocal
     * for each database operation, ensuring proper routing to central or tenant-specific databases.
     */
    @Bean
    fun mongoDatabaseFactory(): org.springframework.data.mongodb.MongoDatabaseFactory {
        return TenantAwareMongoDatabaseFactory(
            centralMongoUri,
            "wms_pro_tenants"
        )
    }

    /**
     * MongoTemplate bean for manual MongoDB operations
     * Uses the tenant-aware factory which handles connection routing per request
     */
    @Bean
    fun mongoTemplate(mongoDatabaseFactory: org.springframework.data.mongodb.MongoDatabaseFactory): MongoTemplate {
        return MongoTemplate(mongoDatabaseFactory)
    }
}