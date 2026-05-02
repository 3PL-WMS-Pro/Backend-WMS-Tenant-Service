package com.wmspro.tenant.billing.defaults

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * TenantBillingDefaults — tenant-wide default rates and FreighAi ChargeType
 * bindings used as the bottom of the rate cascade.
 *
 * Phase A introduces this so customer profiles can leave their rate fields
 * blank and inherit the tenant-level value. The cascade for each rate is:
 *
 *   project.<rate>     (per-project override)
 *      ?: customer.<rate>   (per-customer override on CustomerBillingProfile)
 *      ?: tenantDefaults.<rate>   (this document)
 *
 * The 3 FreighAi ChargeType IDs are also held here because they are tenant-
 * wide constants (CHG-00114 / CHG-00066 / CHG-00067 for Infinity Logistics)
 * — every customer's profile previously stored these redundantly.
 *
 * Singleton-by-convention: a single document per tenant DB with the literal
 * id "DEFAULTS". The service uses a get-or-create pattern; admin upserts via
 * PUT on the controller.
 */
@Document(collection = "tenant_billing_defaults")
data class TenantBillingDefaults(
    @Id
    val id: String = SINGLETON_ID,

    /** Default storage rate; bottom of the storage cascade. */
    val defaultStorageRatePerCbmDay: BigDecimal,

    /** Default inbound (handling-in) rate per CBM; bottom of the inbound cascade. */
    val defaultInboundCbmRate: BigDecimal,

    /** Default outbound (handling-out) rate per CBM; bottom of the outbound cascade. */
    val defaultOutboundCbmRate: BigDecimal,

    /** Optional tenant-wide monthly minimum (storage subtotal floor). */
    val defaultMonthlyMinimum: BigDecimal? = null,

    /** FreighAi ChargeType ID for storage lines (e.g. "CHG-00114"). */
    val freighaiStorageChargeTypeId: String,

    /** FreighAi ChargeType ID for inbound (handling-in) movement lines. */
    val freighaiInboundMovementChargeTypeId: String,

    /** FreighAi ChargeType ID for outbound (handling-out) movement lines. */
    val freighaiOutboundMovementChargeTypeId: String,

    val updatedAt: Instant = Instant.now(),
    val updatedBy: String = "system"
) {
    companion object {
        const val SINGLETON_ID = "DEFAULTS"
    }
}
