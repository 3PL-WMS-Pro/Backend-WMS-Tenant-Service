package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime
import java.util.UUID
import jakarta.validation.constraints.*

/**
 * ECommercePlatformConfig Model - Stores e-commerce platform integration configuration for each tenant
 * Collection: ecommerce_platform_configs
 * Database: Central shared database (NOT tenant-specific)
 */
@Document(collection = "ecommerce_platform_configs")
data class ECommercePlatformConfig(
    @Id
    val id: String = UUID.randomUUID().toString(),

    @Indexed
    val tenantId: Int,

    @field:NotBlank(message = "Platform type cannot be blank")
    val platformType: String, // e.g., "Shopify", "WooCommerce", "Custom", etc.

    @field:NotBlank(message = "API key cannot be blank")
    val apiKey: String, // Stored encrypted

    val apiVersion: String? = null,

    @field:NotBlank(message = "API secret cannot be blank")
    val apiSecret: String, // Stored encrypted

    @field:NotBlank(message = "Store URL cannot be blank")
    val storeUrl: String,

    val syncOptions: Map<String, Any> = emptyMap(), // JSON object for sync options (Orders, Customer, Inventory, Products)

    val status: ECommercePlatformConfigStatus = ECommercePlatformConfigStatus.ACTIVE,

    val connectionStatus: ConnectionStatus = ConnectionStatus.NOT_TESTED,

    @CreatedDate
    val createdAt: LocalDateTime? = null,

    @LastModifiedDate
    val updatedAt: LocalDateTime? = null
) {
    init {
        require(tenantId > 0) { "Tenant ID must be positive" }
        require(platformType.isNotBlank()) { "Platform type cannot be blank" }
        require(apiKey.isNotBlank()) { "API key cannot be blank" }
        require(apiSecret.isNotBlank()) { "API secret cannot be blank" }
        require(storeUrl.isNotBlank()) { "Store URL cannot be blank" }
    }
}

/**
 * E-Commerce Platform Configuration Status
 */
enum class ECommercePlatformConfigStatus {
    ACTIVE,
    INACTIVE
}
