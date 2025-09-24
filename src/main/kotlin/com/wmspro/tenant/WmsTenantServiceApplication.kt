package com.wmspro.tenant

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cloud.client.discovery.EnableDiscoveryClient
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.mongodb.config.EnableMongoAuditing
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import com.wmspro.tenant.interceptor.TenantInterceptor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.annotation.Value

/**
 * Main application class for WMS Tenant Service
 * This service manages tenant isolation, database routing, user roles, and team configurations
 *
 * Excludes MongoDB auto-configurations to allow custom multi-tenant database configuration
 */
@SpringBootApplication(exclude = [
    MongoAutoConfiguration::class,
    MongoDataAutoConfiguration::class
])
@EnableDiscoveryClient
@EnableMongoRepositories
@EnableMongoAuditing
@EnableCaching
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@ComponentScan(basePackages = [
    "com.wmspro.tenant",
    "com.wmspro.common"
])
class WmsTenantServiceApplication

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger(WmsTenantServiceApplication::class.java)

    try {
        val context = runApplication<WmsTenantServiceApplication>(*args)
        val port = context.environment.getProperty("server.port")
        val appName = context.environment.getProperty("spring.application.name")

        logger.info("""

            ╔════════════════════════════════════════════╗
            ║    WMS TENANT SERVICE STARTED              ║
            ╠════════════════════════════════════════════╣
            ║    Application: $appName
            ║    Port: $port
            ║    Profile: ${context.environment.activeProfiles.joinToString()}
            ║    Eureka: Enabled
            ║    MongoDB: Connected
            ╚════════════════════════════════════════════════╝

        """.trimIndent())

        logger.info("Tenant Service is ready to handle multi-tenant operations")

    } catch (e: Exception) {
        logger.error("Failed to start WMS Tenant Service", e)
        throw e
    }
}

/**
 * Web MVC Configuration for the Tenant Service
 */
@Configuration
class WebMvcConfig(
    private val tenantInterceptor: TenantInterceptor,  // Complete interceptor for tenant handling
    @Value("\${app.cors.allowed-origins}") private val allowedOrigins: List<String>,
    @Value("\${app.cors.allowed-methods}") private val allowedMethods: String,
    @Value("\${app.cors.allowed-headers}") private val allowedHeaders: String,
    @Value("\${app.cors.allow-credentials}") private val allowCredentials: Boolean,
    @Value("\${app.cors.max-age}") private val maxAge: Long
) : WebMvcConfigurer {

    private val logger = LoggerFactory.getLogger(WebMvcConfig::class.java)

    override fun addInterceptors(registry: InterceptorRegistry) {
        logger.info("Registering tenant interceptor for multi-tenant support")

        // Single interceptor handles both tenant extraction and MongoDB connection
        registry.addInterceptor(tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/actuator/**",
                "/swagger-ui/**",
                "/v3/api-docs/**"
            )
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        logger.info("Configuring CORS with allowed origins: $allowedOrigins")
        registry.addMapping("/**")
            .allowedOriginPatterns(*allowedOrigins.toTypedArray())
            .allowedMethods(*allowedMethods.split(",").toTypedArray())
            .allowedHeaders(allowedHeaders)
            .allowCredentials(allowCredentials)
            .maxAge(maxAge)
    }
}
