package com.wmspro.tenant.billing.costs

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * TenantOperationalCosts — tenant-wide internal cost rates.
 *
 * Phase B introduces this as the cost-side mirror of [TenantBillingDefaults].
 * These numbers represent what it COSTS the tenant to execute storage,
 * inbound handling, and outbound handling — used by the billing run to
 * write [BillingRunCostSnapshot] records alongside each invoice line.
 *
 * Internal-only:
 *   - Customer-facing invoices never include cost data.
 *   - FreighAi never sees these numbers.
 *   - Reconciliation reports (Phase D) join cost snapshots with invoice
 *     lines to compute margin per shipment.
 *
 * Singleton-by-convention: a single document per tenant DB with the
 * literal id "COSTS". Service uses get-or-create on upsert.
 */
@Document(collection = "tenant_operational_costs")
data class TenantOperationalCosts(
    @Id
    val id: String = SINGLETON_ID,

    /** Cost per CBM-day for storage (e.g. labour + space allocation). */
    val baseStorageCostPerCbmDay: BigDecimal,

    /** Cost per CBM for inbound (handling-in) operations. */
    val baseInboundCostPerCbm: BigDecimal,

    /** Cost per CBM for outbound (handling-out) operations. */
    val baseOutboundCostPerCbm: BigDecimal,

    val updatedAt: Instant = Instant.now(),
    val updatedBy: String = "system"
) {
    companion object {
        const val SINGLETON_ID = "COSTS"
    }
}
