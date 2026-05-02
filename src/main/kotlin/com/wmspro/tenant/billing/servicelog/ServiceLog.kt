package com.wmspro.tenant.billing.servicelog

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * ServiceLog — record of a value-added service WMS performed for a customer.
 *
 * Reserved for ad-hoc / non-routine ops (palletization, repackaging, crane
 * rental, special handling, etc.). NOT used for routine inbound/outbound
 * CBM movement — that's the movement category (auto-derived from GRN/GIN
 * completion in the billing engine).
 *
 * Each log is attached to exactly one of a GRN (ReceivingRecord) or a GIN
 * (OrderFulfillmentRequest). The billing engine (Phase 5) sums quantities
 * per `serviceCode` for a (customer, month) and bills at the subscription's
 * resolved rate (custom override → catalog standard).
 *
 * Editable / deletable while `billingInvoiceId` is null. Once locked to a
 * SUBMITTED billing run, the log is immutable until the run is cancelled.
 */
@Document(collection = "service_log")
@CompoundIndex(name = "customer_performedAt_idx", def = "{'customerId': 1, 'performedAt': 1}")
@CompoundIndex(name = "billing_invoice_idx", def = "{'billingInvoiceId': 1}")
@CompoundIndex(name = "attached_to_idx", def = "{'attachedTo.id': 1}")
data class ServiceLog(
    @Id
    val serviceLogId: String,

    @Indexed
    val customerId: Long,

    /** FK to [com.wmspro.tenant.billing.catalog.ServiceCatalog.serviceCode]. */
    @Indexed
    val serviceCode: String,

    val quantity: BigDecimal,

    /**
     * Optional per-log rate override. When set, takes precedence over the
     * customer subscription's customRatePerUnit and the catalog's standardRatePerUnit
     * for billing this single entry. Null = use the cascade
     * (subscription → catalog).
     *
     * Invoice presentation still collapses to one line per serviceCode; the
     * line's rate is the blended effective rate (totalSubtotal / totalQty).
     * Per-log overrides are preserved on the model for audit and Excel
     * export, never overwritten by the billing engine.
     */
    val customRatePerUnit: BigDecimal? = null,

    /**
     * Phase C — optional per-log COST override. Internal-only.
     *
     * When set, takes precedence over [com.wmspro.tenant.billing.catalog.ServiceCatalog.standardCostPerUnit]
     * for THIS specific log entry. Null = use the catalog default.
     *
     * Cost is purely internal — never appears on the customer-facing
     * invoice or in any payload sent to FreighAi. Used by the billing
     * engine to write per-log cost into [BillingRunCostSnapshot] for
     * later reconciliation reporting.
     *
     * Option A (absolute override) per the Phase 13 design call: the
     * stored value REPLACES the catalog cost rather than adding to it.
     * Reasons live in the existing `notes` field as free text.
     */
    val customCostPerUnit: BigDecimal? = null,

    /** User-entered date the service was performed. May be backdated. */
    val performedAt: LocalDate,

    /** Exactly one of GRN or GIN — see [AttachedRef]. */
    val attachedTo: AttachedRef,

    /** Acting user's email; immutable after create. */
    val performedBy: String,

    /** Audit timestamp — when the log was first persisted. */
    @CreatedDate
    val loggedAt: Instant? = null,

    val notes: String? = null,

    /**
     * Set by the billing engine when this log is included in a SUBMITTED
     * billing run. Locks the log from further mutation.
     */
    val billingInvoiceId: String? = null,

    /**
     * If `performedAt` lands in a month already covered by a SUBMITTED
     * WmsBillingInvoice, this field carries the original month (e.g.
     * "2026-04"). The billing engine then attributes the charge to the
     * NEXT month's invoice and renders a "carried-over from {month}" note
     * on the line. Auto-set during ServiceLog creation when relevant.
     */
    val carriedOverFromMonth: String? = null,

    val updatedAt: Instant? = null,
    val updatedBy: String? = null
)

/**
 * AttachedRef — the operational record this service was performed against.
 *
 * Validation enforced in [ServiceLogService]:
 *   - `type` must be GRN or GIN
 *   - `id` must be non-blank
 *   - `number` is the human-friendly display string (e.g. "GRN-RCV-042")
 *
 * Stored as a sub-document so MongoDB can index on `attachedTo.id` for
 * fast lookups from the GRN/GIN detail screens' Service Charges panels.
 */
data class AttachedRef(
    val type: AttachedType,
    val id: String,
    val number: String
)

enum class AttachedType {
    GRN, GIN
}
