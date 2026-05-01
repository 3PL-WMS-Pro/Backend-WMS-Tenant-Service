package com.wmspro.tenant.billing.profile

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * CustomerBillingProfile — per-customer billing configuration.
 *
 * Each entry represents the billing setup for one WMS customer (identified by
 * the synthetic Long that the customer-master proxy issues — see
 * AccountIdMapping). The profile is created the first time an admin saves
 * billing details for that customer; absent profile = customer is not billed.
 *
 * Three independent rate axes:
 *   - Storage           (CBM-day occupancy × `cbmRatePerDay`)
 *   - Inbound movement  (CBM received via GRN × `inboundCbmRate`)
 *   - Outbound movement (CBM shipped via GIN × `outboundCbmRate`)
 *
 * Each rate has a customer-level default and an optional per-project override
 * (see [ProjectRate]). Storage default is required. Movement defaults are
 * nullable — null means "no movement charge for unprojected GRNs/GINs".
 *
 * Service subscriptions ([ServiceSubscription]) reference [ServiceCatalog]
 * entries and may carry a per-customer rate override; null override = use the
 * catalog's `standardRatePerUnit`.
 *
 * The three `freighai*ChargeTypeId` fields bind storage/inbound/outbound
 * invoice lines to specific FreighAi `ChargeType` documents. They're validated
 * against FreighAi at upsert time so the binding can never drift to a deleted
 * or typo'd ID.
 *
 * Currency is implicit: WMS is AED-only today. If multi-currency is ever
 * needed, add `currency: String` here and resolve from the FreighAi tenant
 * context at billing time.
 */
@Document(collection = "customer_billing_profile")
data class CustomerBillingProfile(
    /** WMS synthetic customer Long (mirrors `AccountIdMapping.id`). */
    @Id
    val customerId: Long,

    /** Storage fallback rate (per CBM-day). Used for items with no projectCode or unknown project. */
    val defaultCbmRatePerDay: BigDecimal,

    /** Inbound movement fallback rate (per CBM received). Null = no charge for unprojected GRNs. */
    val defaultInboundCbmRate: BigDecimal? = null,

    /** Outbound movement fallback rate (per CBM shipped). Null = no charge for unprojected GINs. */
    val defaultOutboundCbmRate: BigDecimal? = null,

    /** Optional storage subtotal floor; if storage subtotal < this, a top-up line is added. */
    val defaultMonthlyMinimum: BigDecimal? = null,

    val projects: List<ProjectRate> = emptyList(),

    val serviceSubscriptions: List<ServiceSubscription> = emptyList(),

    /** FreighAi ChargeType bound to storage invoice lines. */
    val freighaiStorageChargeTypeId: String,

    /** FreighAi ChargeType bound to inbound movement invoice lines. */
    val freighaiInboundMovementChargeTypeId: String,

    /** FreighAi ChargeType bound to outbound movement invoice lines. */
    val freighaiOutboundMovementChargeTypeId: String,

    /**
     * Cron skips customers where this is false. Profiles can be created with
     * `billingEnabled=false` (config draft mode) and turned on later.
     */
    val billingEnabled: Boolean = false,

    @CreatedDate
    val createdAt: Instant? = null,

    @LastModifiedDate
    val updatedAt: Instant? = null,

    val createdBy: String? = null,
    val updatedBy: String? = null
)

/**
 * Per-project rate override.
 *
 * Resolution at billing time:
 *   - storage:  `project.cbmRatePerDay`
 *   - inbound:  `project.inboundCbmRate ?? customer.defaultInboundCbmRate`  (null → no inbound charge)
 *   - outbound: `project.outboundCbmRate ?? customer.defaultOutboundCbmRate` (null → no outbound charge)
 *
 * `projectCode` is unique within a profile and matches the regex
 * `^[A-Z][A-Z0-9_]*$` (validated on the request DTO; embedded here as plain
 * String to keep the model lean).
 */
data class ProjectRate(
    val projectCode: String,
    val label: String,
    val cbmRatePerDay: BigDecimal,
    val inboundCbmRate: BigDecimal? = null,
    val outboundCbmRate: BigDecimal? = null,
    val isActive: Boolean = true
)

/**
 * Per-customer service subscription with optional rate override.
 *
 * `serviceCode` references [com.wmspro.tenant.billing.catalog.ServiceCatalog.serviceCode]
 * and is validated to exist & be active at write time.
 *
 * Resolution at billing time:
 *   `subscription.customRatePerUnit ?? serviceCatalog.standardRatePerUnit`
 */
data class ServiceSubscription(
    val serviceCode: String,
    val customRatePerUnit: BigDecimal? = null,
    val isActive: Boolean = true
)
