package com.wmspro.tenant.billing.reconciliation

import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Read-side service over [BillingRunCostSnapshot] for reconciliation
 * reporting. Pure projection — no mutations.
 */
@Service
class ReconciliationService(
    private val costSnapshotRepository: BillingRunCostSnapshotRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun reportForCustomer(customerId: Long, billingMonth: String): ReconciliationReportResponse {
        val snapshots = costSnapshotRepository.findByCustomerIdAndBillingMonth(customerId, billingMonth)
        return buildReport(customerId = customerId, billingMonth = billingMonth, snapshots = snapshots)
    }

    fun reportForTenant(billingMonth: String): ReconciliationReportResponse {
        val snapshots = costSnapshotRepository.findByBillingMonth(billingMonth)
        return buildReport(customerId = null, billingMonth = billingMonth, snapshots = snapshots)
    }

    private fun buildReport(
        customerId: Long?,
        billingMonth: String,
        snapshots: List<BillingRunCostSnapshot>
    ): ReconciliationReportResponse {
        val rows = snapshots.map { it.toRow() }
        val totalRevenue = rows.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.revenueAmount) }
            .setScale(2, RoundingMode.HALF_UP)
        val totalCost = rows.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.totalCost ?: BigDecimal.ZERO) }
            .setScale(2, RoundingMode.HALF_UP)
        val totalMargin = totalRevenue.subtract(totalCost).setScale(2, RoundingMode.HALF_UP)
        val marginPercent = if (totalRevenue.signum() > 0) {
            totalMargin.multiply(BigDecimal(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
        } else null
        val rowsWithMissingCost = rows.count { it.totalCost == null }

        return ReconciliationReportResponse(
            customerId = customerId,
            billingMonth = billingMonth,
            rows = rows,
            summary = ReconciliationSummary(
                totalRevenue = totalRevenue,
                totalCost = totalCost,
                totalMargin = totalMargin,
                marginPercent = marginPercent,
                rowCount = rows.size,
                rowsWithMissingCost = rowsWithMissingCost
            )
        )
    }

    /**
     * CSV export — one row per snapshot. Excel opens this natively.
     */
    fun reportCsvForCustomer(customerId: Long, billingMonth: String): String {
        val report = reportForCustomer(customerId, billingMonth)
        return report.toCsv()
    }

    private fun BillingRunCostSnapshot.toRow() = ReconciliationRow(
        sourceType = sourceType,
        sourceId = sourceRecord.id,
        sourceNumber = sourceRecord.number,
        serviceCode = serviceCode,
        projectCode = projectCode,
        quantity = quantity,
        unit = unit,
        baseCostRate = baseCostRate,
        adjustments = adjustments,
        effectiveCostRate = effectiveCostRate,
        totalCost = totalCost,
        revenueRate = revenueRate,
        revenueAmount = revenueAmount,
        margin = margin
    )

    private fun ReconciliationReportResponse.toCsv(): String {
        val sb = StringBuilder()
        sb.appendLine(
            listOf(
                "Source Type", "Source ID", "Source Number", "Service Code",
                "Project Code", "Quantity", "Unit",
                "Base Cost Rate", "Adjustments", "Effective Cost Rate", "Total Cost",
                "Revenue Rate", "Revenue Amount", "Margin"
            ).joinToString(",")
        )
        for (r in rows) {
            val adjSummary = r.adjustments
                .joinToString(" | ") { "${it.reason}:${it.ratePerUnitDelta}" }
            sb.appendLine(
                listOf(
                    r.sourceType.name,
                    csvEscape(r.sourceId),
                    csvEscape(r.sourceNumber ?: ""),
                    csvEscape(r.serviceCode ?: ""),
                    csvEscape(r.projectCode ?: ""),
                    r.quantity.toPlainString(),
                    csvEscape(r.unit),
                    r.baseCostRate?.toPlainString() ?: "",
                    csvEscape(adjSummary),
                    r.effectiveCostRate?.toPlainString() ?: "",
                    r.totalCost?.toPlainString() ?: "",
                    r.revenueRate.toPlainString(),
                    r.revenueAmount.toPlainString(),
                    r.margin?.toPlainString() ?: ""
                ).joinToString(",")
            )
        }
        // Footer summary as comments — not strict CSV but Excel-friendly.
        sb.appendLine()
        sb.appendLine("# Summary")
        sb.appendLine("# Total Revenue,${summary.totalRevenue.toPlainString()}")
        sb.appendLine("# Total Cost,${summary.totalCost.toPlainString()}")
        sb.appendLine("# Total Margin,${summary.totalMargin.toPlainString()}")
        sb.appendLine("# Margin %,${summary.marginPercent?.toPlainString() ?: "—"}")
        sb.appendLine("# Rows,${summary.rowCount}")
        sb.appendLine("# Rows with missing cost data,${summary.rowsWithMissingCost}")
        return sb.toString()
    }

    private fun csvEscape(s: String): String {
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }
}
