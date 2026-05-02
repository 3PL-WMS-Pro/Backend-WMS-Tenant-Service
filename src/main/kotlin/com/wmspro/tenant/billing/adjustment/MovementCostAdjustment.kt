package com.wmspro.tenant.billing.adjustment

import com.wmspro.tenant.billing.invoice.MovementDirection
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant

/**
 * MovementCostAdjustment — internal-only per-shipment cost surcharge or
 * credit attached to a single GRN (inbound) or GIN (outbound).
 *
 * Use cases (from the Phase 12 spec): forklift involvement, overtime,
 * special equipment, late-night surcharges. Each adjustment carries a
 * reason (free-text recommendation: FORKLIFT / OVERTIME / EQUIPMENT /
 * OTHER), a signed `ratePerUnitDelta` (per-CBM delta — positive for
 * surcharges, negative for credits), and free-text notes.
 *
 * Resolution at billing-run time:
 *   effectiveCostRate = tenantCost.baseInbound/OutboundCostPerCbm
 *                       + Σ(adjustments.ratePerUnitDelta where attachedTo == this GRN/GIN)
 *
 * The adjustment never appears on the customer-facing invoice — invoice
 * lines stay at the customer-quoted rate. Adjustments only flow into the
 * cost-side BillingRunCostSnapshot for reconciliation.
 *
 * Lock semantics mirror ServiceLog: once `billingInvoiceId` is set
 * (i.e. snapshot was written into a SUBMITTED billing invoice), the
 * adjustment is immutable until the invoice is cancelled.
 */
@Document(collection = "movement_cost_adjustment")
@CompoundIndex(name = "attached_to_idx", def = "{'attachedTo.id': 1}")
@CompoundIndex(name = "billing_invoice_idx", def = "{'billingInvoiceId': 1}")
@CompoundIndex(name = "customer_created_idx", def = "{'customerId': 1, 'createdAt': 1}")
data class MovementCostAdjustment(
    @Id
    val adjustmentId: String,

    @Indexed
    val customerId: Long,

    /** GRN or GIN this adjustment applies to. */
    val attachedTo: AdjustmentAttachedRef,

    /** Direction must match the attached record's nature (INBOUND ↔ GRN, OUTBOUND ↔ GIN). */
    val direction: MovementDirection,

    /**
     * Short reason tag. Recommended values: FORKLIFT, OVERTIME, EQUIPMENT,
     * OTHER. Free text allowed for V1 — admin-only screen so no need to
     * lock to an enum yet.
     */
    val reason: String,

    /**
     * Signed per-unit (per-CBM) delta added on top of the tenant cost
     * default. Positive = surcharge; negative = credit/discount on cost.
     */
    val ratePerUnitDelta: BigDecimal,

    val notes: String? = null,

    val createdBy: String,

    @CreatedDate
    val createdAt: Instant? = null,

    val updatedAt: Instant? = null,
    val updatedBy: String? = null,

    /** Set by billing run when this adjustment is included in a SUBMITTED invoice. */
    val billingInvoiceId: String? = null
)

data class AdjustmentAttachedRef(
    val type: AdjustmentAttachedType,
    val id: String,
    val number: String
)

enum class AdjustmentAttachedType { GRN, GIN }
