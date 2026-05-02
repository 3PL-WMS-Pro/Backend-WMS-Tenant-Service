package com.wmspro.tenant.billing.profile

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

private const val PROJECT_CODE_PATTERN = "^[A-Z][A-Z0-9_]*$"

/**
 * Full upsert request — `PUT /api/v1/billing-profiles/{customerId}`.
 *
 * The full state of the profile (including projects + subscriptions) is sent
 * each time. Granular endpoints exist for callers that want to mutate a
 * single project or subscription without resending everything (see
 * [AddProjectRateRequest], [UpdateProjectRateRequest], etc.) but the WMS
 * Billing-tab UI uses this full upsert for simplicity (single Save click).
 */
data class UpsertCustomerBillingProfileRequest(
    /** Phase A: optional. Null → inherit from TenantBillingDefaults. */
    @field:PositiveOrZero(message = "defaultCbmRatePerDay must be ≥ 0")
    val defaultCbmRatePerDay: BigDecimal? = null,

    @field:PositiveOrZero(message = "defaultInboundCbmRate must be ≥ 0")
    val defaultInboundCbmRate: BigDecimal? = null,

    @field:PositiveOrZero(message = "defaultOutboundCbmRate must be ≥ 0")
    val defaultOutboundCbmRate: BigDecimal? = null,

    @field:PositiveOrZero(message = "defaultMonthlyMinimum must be ≥ 0")
    val defaultMonthlyMinimum: BigDecimal? = null,

    @field:Valid
    val projects: List<ProjectRateInput> = emptyList(),

    @field:Valid
    val serviceSubscriptions: List<ServiceSubscriptionInput> = emptyList(),

    /** Phase A: optional. Null → inherit from TenantBillingDefaults. */
    val freighaiStorageChargeTypeId: String? = null,

    /** Phase A: optional. Null → inherit from TenantBillingDefaults. */
    val freighaiInboundMovementChargeTypeId: String? = null,

    /** Phase A: optional. Null → inherit from TenantBillingDefaults. */
    val freighaiOutboundMovementChargeTypeId: String? = null,

    val billingEnabled: Boolean = false
)

/**
 * Embedded project rate shape used inside [UpsertCustomerBillingProfileRequest].
 *
 * `@Valid` annotation on the parent List enables validation of these nested
 * objects.
 */
data class ProjectRateInput(
    @field:NotBlank(message = "projectCode is required")
    @field:Pattern(
        regexp = PROJECT_CODE_PATTERN,
        message = "projectCode must be uppercase letters/digits/underscores starting with a letter"
    )
    @field:Size(max = 64, message = "projectCode must be at most 64 characters")
    val projectCode: String,

    @field:NotBlank(message = "Project label is required")
    @field:Size(max = 200, message = "label must be at most 200 characters")
    val label: String,

    /** Phase A: optional. Null → cascade to customer default → tenant default. */
    @field:PositiveOrZero(message = "cbmRatePerDay must be ≥ 0")
    val cbmRatePerDay: BigDecimal? = null,

    @field:PositiveOrZero(message = "inboundCbmRate must be ≥ 0")
    val inboundCbmRate: BigDecimal? = null,

    @field:PositiveOrZero(message = "outboundCbmRate must be ≥ 0")
    val outboundCbmRate: BigDecimal? = null,

    val isActive: Boolean = true
)

data class ServiceSubscriptionInput(
    @field:NotBlank(message = "serviceCode is required")
    val serviceCode: String,

    @field:PositiveOrZero(message = "customRatePerUnit must be ≥ 0")
    val customRatePerUnit: BigDecimal? = null,

    val isActive: Boolean = true
)

// ──────────────────────────────────────────────────────────────────────────
// Granular project-level requests (single-row mutation)
// ──────────────────────────────────────────────────────────────────────────

/** `POST /api/v1/billing-profiles/{customerId}/projects` body. */
data class AddProjectRateRequest(
    @field:NotBlank(message = "projectCode is required")
    @field:Pattern(regexp = PROJECT_CODE_PATTERN, message = "projectCode must match $PROJECT_CODE_PATTERN")
    @field:Size(max = 64)
    val projectCode: String,

    @field:NotBlank(message = "label is required")
    @field:Size(max = 200)
    val label: String,

    @field:PositiveOrZero
    val cbmRatePerDay: BigDecimal? = null,

    @field:PositiveOrZero
    val inboundCbmRate: BigDecimal? = null,

    @field:PositiveOrZero
    val outboundCbmRate: BigDecimal? = null,

    val isActive: Boolean = true
)

/** `PUT /api/v1/billing-profiles/{customerId}/projects/{projectCode}` body. projectCode is path-bound. */
data class UpdateProjectRateRequest(
    @field:NotBlank(message = "label is required")
    @field:Size(max = 200)
    val label: String,

    @field:PositiveOrZero
    val cbmRatePerDay: BigDecimal? = null,

    @field:PositiveOrZero
    val inboundCbmRate: BigDecimal? = null,

    @field:PositiveOrZero
    val outboundCbmRate: BigDecimal? = null,

    val isActive: Boolean = true
)

// ──────────────────────────────────────────────────────────────────────────
// Granular subscription requests
// ──────────────────────────────────────────────────────────────────────────

/** `POST /api/v1/billing-profiles/{customerId}/services` body. */
data class AddServiceSubscriptionRequest(
    @field:NotBlank(message = "serviceCode is required")
    val serviceCode: String,

    @field:PositiveOrZero
    val customRatePerUnit: BigDecimal? = null,

    val isActive: Boolean = true
)

/** `PUT /api/v1/billing-profiles/{customerId}/services/{serviceCode}` body. serviceCode is path-bound. */
data class UpdateServiceSubscriptionRequest(
    @field:PositiveOrZero
    val customRatePerUnit: BigDecimal? = null,

    val isActive: Boolean = true
)

/** `POST /api/v1/billing-profiles/{customerId}/billing-enabled` body. */
data class SetBillingEnabledRequest(
    val enabled: Boolean
)

// ──────────────────────────────────────────────────────────────────────────
// Response shapes
// ──────────────────────────────────────────────────────────────────────────

data class CustomerBillingProfileResponse(
    val customerId: Long,
    val defaultCbmRatePerDay: BigDecimal?,
    val defaultInboundCbmRate: BigDecimal?,
    val defaultOutboundCbmRate: BigDecimal?,
    val defaultMonthlyMinimum: BigDecimal?,
    val projects: List<ProjectRateResponse>,
    val serviceSubscriptions: List<ServiceSubscriptionResponse>,
    val freighaiStorageChargeTypeId: String?,
    val freighaiInboundMovementChargeTypeId: String?,
    val freighaiOutboundMovementChargeTypeId: String?,
    val billingEnabled: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val createdBy: String?,
    val updatedBy: String?
)

data class ProjectRateResponse(
    val projectCode: String,
    val label: String,
    val cbmRatePerDay: BigDecimal?,
    val inboundCbmRate: BigDecimal?,
    val outboundCbmRate: BigDecimal?,
    val isActive: Boolean
)

data class ServiceSubscriptionResponse(
    val serviceCode: String,
    val customRatePerUnit: BigDecimal?,
    val isActive: Boolean
)
