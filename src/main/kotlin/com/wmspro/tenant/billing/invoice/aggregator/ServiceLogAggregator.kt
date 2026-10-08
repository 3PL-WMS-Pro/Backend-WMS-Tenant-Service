package com.wmspro.tenant.billing.invoice.aggregator

import com.wmspro.tenant.billing.servicelog.AttachedType
import com.wmspro.tenant.billing.servicelog.ServiceLog
import com.wmspro.tenant.billing.servicelog.ServiceLogRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Aggregates unbilled ServiceLog entries per (serviceCode, projectCode)
 * for a given (customer, month). Phase G: returns one
 * [AggregatedServiceLine] per (serviceCode, projectCode) pair so the
 * billing engine can slice them into per-project invoices. The invoice
 * line builder folds the entries into a single line per pair, computing
 * a blended effective rate so that `qty × ratePerUnit ≈ amount` while
 * still respecting each log's override for the underlying subtotal math.
 */
@Component
class ServiceLogAggregator(
    private val repository: ServiceLogRepository,
    private val warehouseAttribution: WarehouseAttribution
) {
    /**
     * @return the service lines per warehouse when [splitByWarehouse] - a log belongs to the
     *         warehouse of the GRN or GIN it is attached to - else everything under the null key.
     */
    fun aggregate(
        customerId: Long,
        billingMonth: YearMonth,
        splitByWarehouse: Boolean = false
    ): Map<String?, Map<ServiceLineKey, AggregatedServiceLine>> {
        val from = billingMonth.atDay(1)
        val to = billingMonth.atEndOfMonth()
        val logs = repository.findUnbilledByCustomerAndDateRange(customerId, from, to)
        if (logs.isEmpty()) return emptyMap()
        if (!splitByWarehouse) return mapOf(null to aggregateLogs(logs))

        val warehouseIds = warehouseAttribution.warehouseNames().keys
        val receiptWarehouses = warehouseAttribution.receivingRecordWarehouses(
            logs.filter { it.attachedTo.type == AttachedType.GRN }.map { it.attachedTo.id }
        )
        val shipmentWarehouses = warehouseAttribution.fulfillmentWarehouses(
            logs.filter { it.attachedTo.type == AttachedType.GIN }.map { it.attachedTo.id },
            warehouseIds
        )
        return logs.groupBy { log ->
            when (log.attachedTo.type) {
                AttachedType.GRN -> receiptWarehouses[log.attachedTo.id]
                AttachedType.GIN -> shipmentWarehouses[log.attachedTo.id]
            }
        }.mapValues { (_, group) -> aggregateLogs(group) }
    }

    private fun aggregateLogs(logs: List<ServiceLog>): Map<ServiceLineKey, AggregatedServiceLine> {
        val byKey = logs.groupBy { ServiceLineKey(it.serviceCode, it.projectCode) }
        return byKey.mapValues { (_, group) ->
            AggregatedServiceLine(
                serviceCode = group.first().serviceCode,
                projectCode = group.first().projectCode,
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
 * Phase G — composite key: services aggregate per (serviceCode, projectCode)
 * so each per-project invoice gets only its own service lines. Null
 * projectCode = default bucket.
 */
data class ServiceLineKey(
    val serviceCode: String,
    val projectCode: String?
)

/**
 * Aggregated rollup for one (serviceCode, projectCode) pair in a (customer, month).
 *
 * `totalQuantity` is the sum of all log quantities; `entries` preserves
 * each log's own quantity + optional rate override so the invoice line
 * builder can compute a per-log subtotal (entry.qty × resolvedRate(entry))
 * and then collapse to a single invoice line per pair with a blended
 * effective rate.
 */
data class AggregatedServiceLine(
    val serviceCode: String,
    val projectCode: String?,
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
