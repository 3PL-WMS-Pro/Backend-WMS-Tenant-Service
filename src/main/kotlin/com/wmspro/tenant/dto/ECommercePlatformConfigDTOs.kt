package com.wmspro.tenant.dto

import com.wmspro.tenant.model.ECommercePlatformConfigStatus
import com.wmspro.tenant.model.ConnectionStatus
import jakarta.validation.constraints.NotBlank
import java.time.LocalDateTime

/**
 * Request DTO for creating e-commerce platform configuration
 */
data class CreateECommercePlatformConfigRequest(
    @field:NotBlank(message = "Platform type cannot be blank")
    val platformType: String,

    @field:NotBlank(message = "API key cannot be blank")
    val apiKey: String,

    val apiVersion: String? = null,

    @field:NotBlank(message = "API secret cannot be blank")
    val apiSecret: String,

    @field:NotBlank(message = "Store URL cannot be blank")
    val storeUrl: String,

    val syncOptions: Map<String, Any> = emptyMap(),

    val status: ECommercePlatformConfigStatus = ECommercePlatformConfigStatus.ACTIVE
)

/**
 * Request DTO for updating e-commerce platform configuration
 */
data class UpdateECommercePlatformConfigRequest(
    @field:NotBlank(message = "Platform type cannot be blank")
    val platformType: String,

    val apiKey: String? = null, // Optional on update - if not provided, keep existing

    val apiVersion: String? = null,

    val apiSecret: String? = null, // Optional on update - if not provided, keep existing

    @field:NotBlank(message = "Store URL cannot be blank")
    val storeUrl: String,

    val syncOptions: Map<String, Any> = emptyMap(),

    val status: ECommercePlatformConfigStatus = ECommercePlatformConfigStatus.ACTIVE
)

/**
 * Response DTO for e-commerce platform configuration
 */
data class ECommercePlatformConfigResponse(
    val id: String,
    val platformType: String,
    val apiKey: String, // Masked for security
    val apiVersion: String?,
    val apiSecret: String, // Masked for security
    val storeUrl: String,
    val syncOptions: Map<String, Any>,
    val status: ECommercePlatformConfigStatus,
    val connectionStatus: ConnectionStatus,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

/**
 * List item DTO for e-commerce platform configuration
 */
data class ECommercePlatformConfigListItem(
    val id: String,
    val platformType: String,
    val storeUrl: String,
    val status: ECommercePlatformConfigStatus,
    val connectionStatus: ConnectionStatus
)

/**
 * List response wrapper
 */
data class ECommercePlatformConfigListResponse(
    val data: List<ECommercePlatformConfigListItem>
)
