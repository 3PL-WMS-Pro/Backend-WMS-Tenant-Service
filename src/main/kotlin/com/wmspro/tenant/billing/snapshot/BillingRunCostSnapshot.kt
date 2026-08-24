package com.wmspro.tenant.billing.snapshot

import com.wmspro.tenant.billing.invoice.MovementDirection
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.Field
import java.math.BigDecimal
import java.time.Instant

/**
 * BillingRunCostSnapshot — frozen cost-side mirror of an invoice line item.
 *
 * One snapshot is written per source record (storage line item, GRN, GIN, or
 * ServiceLog) per billing run. Captures the resolved cost rate, any per-
 * shipment adjustments (Phase C), the effective cost rate, and the matching
 * revenue figures from the invoice. Reconciliation reports (Phase D) join
 * `(billingInvoiceId, sourceRecord.id)` back to the invoice line.
 *
 * Lifecycle:
 *   - Written during billing-run generation, alongside WmsBillingInvoice.
 *   - Immutable from creation — no update API.
 *   - Deleted during billing-run cancellation (mirrors invoice line lock-clear).
 *
 * Internal-only: customer never sees this; FreighAi never sees this.
 */
@Document(collection = "billing_run_cost_snapshot")
@CompoundIndex(name = "invoice_idx", def = "{'billingInvoiceId': 1}")
@CompoundIndex(name = "customer_month_idx", def = "{'customerId': 1, 'billingMonth': 1}")
@CompoundIndex(name = "source_idx", def = "{'sourceRecord.id': 1}")
@CompoundIndex(
    name = "wj_v1_cost_line_uq",
    def = "{'billingInvoiceId':1,'costLineId':1}",
    unique = true,
    partialFilter = "{'generationContractVersion':'WAREHOUSE_JOB_V1'}"
)
data class BillingRunCostSnapshot(
    @Id
    val snapshotId: String,

    @Indexed
    val billingInvoiceId: String,

    @Indexed
    val customerId: Long,

    /** Format: "YYYY-MM" (e.g. "2026-04"). */
    val billingMonth: String,

    /** Which billing axis this snapshot corresponds to. */
    val sourceType: SnapshotSourceType,

    /** Reference to the underlying record (storage item id, GRN id, GIN id, or service log id). */
    val sourceRecord: SnapshotRef,

    /** Set when sourceType == SERVICE; null otherwise. */
    val serviceCode: String? = null,

    /** Project tag carried over from the source record (for reconciliation grouping). */
    val projectCode: String? = null,

    /** CBM, CBM-days, or service quantity depending on sourceType. */
    val quantity: BigDecimal,

    /** Display unit ("CBM", "CBM-day", "pallet", etc.) — mirrors the invoice line. */
    val unit: String,

    /** Resolved base cost rate at billing-run time. Null when no tenant default was configured. */
    val baseCostRate: BigDecimal?,

    /** Per-shipment adjustments (Phase C) frozen at run time. Empty list when none apply. */
    val adjustments: List<CostAdjustmentSnapshot> = emptyList(),

    /** baseCostRate + Σ(adjustments). Null when baseCostRate is null. */
    val effectiveCostRate: BigDecimal?,

    /** quantity × effectiveCostRate, rounded to 2dp. Null when effectiveCostRate is null. */
    val totalCost: BigDecimal?,

    /** Mirror of the invoice line's resolved rate. */
    val revenueRate: BigDecimal,

    /** Mirror of the invoice line's amount. */
    val revenueAmount: BigDecimal,

    /** revenueAmount − totalCost. Null when totalCost is null. */
    val margin: BigDecimal?,

    val snapshotAt: Instant = Instant.now(),

    /** Additive V1 provenance; absent on every pre-cutover snapshot. */
    @Field(write = Field.Write.NON_NULL)
    val generationContractVersion: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val costLineId: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val sourceLineId: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val costTreatment: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val freighaiChargeTypeId: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val completionWeight: BigDecimal? = null,
    @Field(write = Field.Write.NON_NULL)
    val calculationVersion: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val carryOverOriginMonth: String? = null,
    @Field(write = Field.Write.NON_NULL)
    val storageProvenance: StorageCostProvenance? = null
)

data class StorageCostProvenance(
    val inventoryType: String,
    val inventoryId: String,
    val warehouseId: String? = null,
    val locationId: String? = null,
    val fromDate: String? = null,
    val toDate: String? = null
)

/**
 * Frozen reference to the source record. Mirrors AttachedRef on ServiceLog
 * but lives here so invoice/snapshot has no dependency on servicelog package.
 */
data class SnapshotRef(
    val type: SnapshotSourceType,
    val id: String,
    val number: String? = null
)

/**
 * Snapshot-time copy of a MovementCostAdjustment. Stored frozen so future
 * edits/deletes of the source adjustment can't shift historical reports.
 */
data class CostAdjustmentSnapshot(
    val sourceAdjustmentId: String,
    val reason: String,
    val ratePerUnitDelta: BigDecimal,
    val notes: String? = null
)

enum class SnapshotSourceType {
    STORAGE,                  // per storage_item or quantity_inventory CBM-day contribution
    INBOUND,                  // per receiving_record (GRN)
    OUTBOUND,                 // per order_fulfillment_request (GIN)
    SERVICE                   // per service_log
}

/**
 * Bridges the snapshot's MovementDirection enum to its broader source type.
 * (Movement adjustments use the existing invoice MovementDirection enum;
 * snapshots use SnapshotSourceType so STORAGE / SERVICE rows can coexist.)
 */
fun MovementDirection.toSnapshotSourceType(): SnapshotSourceType = when (this) {
    MovementDirection.INBOUND -> SnapshotSourceType.INBOUND
    MovementDirection.OUTBOUND -> SnapshotSourceType.OUTBOUND
}
