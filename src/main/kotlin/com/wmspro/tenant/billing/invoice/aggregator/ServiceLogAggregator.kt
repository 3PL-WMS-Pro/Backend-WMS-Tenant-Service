package com.wmspro.tenant.billing.invoice.aggregator

import com.wmspro.tenant.billing.servicelog.ServiceLogRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Aggregates unbilled ServiceLog quantities per `serviceCode` for a given
 * (customer, month). Trivial compared to OccupancyAggregator and
 * MovementAggregator since ServiceLog is a typed Mongo collection that
 * Tenant Service owns directly.
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
                quantity = group.fold(BigDecimal.ZERO) { acc, log -> acc.add(log.quantity) },
                serviceLogIds = group.map { it.serviceLogId }
            )
        }
    }
}

data class AggregatedServiceLine(
    val quantity: BigDecimal,
    val serviceLogIds: List<String>
)
