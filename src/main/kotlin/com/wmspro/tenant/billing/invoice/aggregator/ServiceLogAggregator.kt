package com.wmspro.tenant.billing.invoice.aggregator

import com.wmspro.tenant.billing.servicelog.ServiceLogRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Aggregates unbilled ServiceLog entries per `serviceCode` for a given
 * (customer, month). Returns one [AggregatedServiceLine] per serviceCode,
 * exposing both the total quantity and the per-log entries (with their
 * optional rate overrides). The invoice line builder folds the entries
 * into a single line per service code, computing a blended effective rate
 * so that `qty × ratePerUnit ≈ amount` on the invoice while still
 * respecting each log's override for the underlying subtotal math.
 */
@Component
class ServiceLogAggregator(
    private val repository: ServiceLogRepository
) {
    fun aggregate(customerId: Long, billingMonth: YearMonth): Map<String, AggregatedServiceLine> {
        val from = billingMonth.atDay(1)
        val to = billingMonth.atEndOfMonth()
        val logs = repository.findUnbilledByCustomerAndDateRange(customerId, from, to)
        if (logs.isEmpty()) return emptyMap()

        val byService = logs.groupBy { it.serviceCode }
        return byService.mapValues { (_, group) ->
            AggregatedServiceLine(
                totalQuantity = group.fold(BigDecimal.ZERO) { acc, log -> acc.add(log.quantity) },
                entries = group.map { log ->
                    ServiceLogEntry(
                        serviceLogId = log.serviceLogId,
                        quantity = log.quantity,
                        customRatePerUnit = log.customRatePerUnit,
                        customCostPerUnit = log.customCostPerUnit
                    )
                },
                serviceLogIds = group.map { it.serviceLogId }
            )
        }
    }
}

/**
 * Aggregated rollup for one serviceCode in a (customer, month).
 *
 * `totalQuantity` is the sum of all log quantities; `entries` preserves
 * each log's own quantity + optional rate override so the invoice line
 * builder can compute a per-log subtotal (entry.qty × resolvedRate(entry))
 * and then collapse to a single invoice line per service code with a
 * blended effective rate.
 */
data class AggregatedServiceLine(
    val totalQuantity: BigDecimal,
    val entries: List<ServiceLogEntry>,
    val serviceLogIds: List<String>
)

/**
 * Per-log slice carried through the aggregator so the line builder can
 * resolve the effective rate on a per-entry basis (override → cascade).
 *
 * Phase C adds `customCostPerUnit` so the cost-snapshot writer can resolve
 * per-log COST the same way it resolves per-log REVENUE.
 */
data class ServiceLogEntry(
    val serviceLogId: String,
    val quantity: BigDecimal,
    val customRatePerUnit: BigDecimal?,
    val customCostPerUnit: BigDecimal? = null
)
