package com.wmspro.tenant.billing.reconciliation

import com.wmspro.tenant.billing.snapshot.CostAdjustmentSnapshot
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import java.math.BigDecimal

/**
 * Reconciliation report response — flattens BillingRunCostSnapshot rows
 * into a finance-friendly per-shipment view with margin computed.
 */
data class ReconciliationReportResponse(
    val customerId: Long?,
    val billingMonth: String,
    val rows: List<ReconciliationRow>,
    val summary: ReconciliationSummary
)

data class ReconciliationRow(
    val sourceType: SnapshotSourceType,
    val sourceId: String,
    val sourceNumber: String?,
    val serviceCode: String?,
    val projectCode: String?,
    val quantity: BigDecimal,
    val unit: String,
    val baseCostRate: BigDecimal?,
    val adjustments: List<CostAdjustmentSnapshot>,
    val effectiveCostRate: BigDecimal?,
    val totalCost: BigDecimal?,
    val revenueRate: BigDecimal,
    val revenueAmount: BigDecimal,
    val margin: BigDecimal?
)

data class ReconciliationSummary(
    val totalRevenue: BigDecimal,
    val totalCost: BigDecimal,
    val totalMargin: BigDecimal,
    /** margin / revenue × 100, 2dp. Null when revenue is zero. */
    val marginPercent: BigDecimal?,
    val rowCount: Int,
    val rowsWithMissingCost: Int
)
