package com.wmspro.tenant.billing.catalog

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * ServiceCatalog — the standardized list of value-added services that the WMS
 * tenant offers customers (palletization, repackaging, crane rental, etc.).
 *
 * Per-tenant collection (`service_catalog`); each tenant configures its own
 * list via the admin Settings → Billing → Service Catalog screen.
 *
 * Each entry binds to a FreighAi `ChargeType` via [freighaiChargeTypeId];
 * VAT and ledger resolution happen on the FreighAi side at invoice creation
 * time. [vatPercent] is an optional override for the rare case where the
 * customer wants this service billed at a non-standard VAT — null means "use
 * whatever FreighAi's ChargeType says".
 *
 * [standardRatePerUnit] is the catalog default. Per-customer overrides are
 * captured separately in `customer_billing_profile.serviceSubscriptions[]`
 * (Phase 2). At billing time the resolved rate is:
 *
 *     subscription.customRatePerUnit ?? catalog.standardRatePerUnit
 *
 * Soft-delete only — [isActive]=false hides from new subscriptions but
 * preserves the entry for historical service-log lookups.
 *
 * @see com.wmspro.common.external.freighai.dto.FreighAiChargeType
 */
@Document(collection = "service_catalog")
data class ServiceCatalog(
    /** Stable uppercase code, e.g. "PALLETIZATION", "REPACKAGING". */
    @Id
    @field:NotBlank(message = "serviceCode is required")
    @field:Size(max = 64, message = "serviceCode must be at most 64 characters")
    val serviceCode: String,

    @field:NotBlank(message = "label is required")
    @field:Size(max = 200, message = "label must be at most 200 characters")
    val label: String,

    /** E.g. "pallet", "carton", "hour", "kg". */
    @field:NotBlank(message = "unit is required")
    @field:Size(max = 32, message = "unit must be at most 32 characters")
    val unit: String,

    /** Default per-unit charge in tenant currency (AED for WMS today). */
    @field:PositiveOrZero(message = "standardRatePerUnit must be ≥ 0")
    val standardRatePerUnit: BigDecimal,

    /** FK to FreighAi `charge_types._id` (e.g. "CHG-00102"). */
    @field:NotBlank(message = "freighaiChargeTypeId is required")
    @Indexed
    val freighaiChargeTypeId: String,

    /** Optional override for FreighAi ChargeType's vatPercent. Null = inherit. */
    val vatPercent: BigDecimal? = null,

    @Indexed
    val isActive: Boolean = true,

    @CreatedDate
    val createdAt: Instant? = null,

    @LastModifiedDate
    val updatedAt: Instant? = null,

    val createdBy: String? = null,
    val updatedBy: String? = null
)
