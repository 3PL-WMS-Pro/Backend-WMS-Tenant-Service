package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItem
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Phase E — origin of a raw FreighAi line, used at consolidation time to
 * decide whether a chargeTypeId group should be merged.
 */
internal enum class FreighAiLineSource { STORAGE, MOVEMENT, SERVICE }

/**
 * A FreighAi line item paired with its origin and original emission index.
 * Input to [consolidateFreighAiLines].
 */
internal data class TaggedFreighAiLine(
    val item: FreighAiInvoiceLineItem,
    val source: FreighAiLineSource,
    val originalIndex: Int
)

/**
 * Phase E — consolidate FreighAi line items by chargeTypeId so internal
 * surcharges (e.g. ServiceCatalog `FORKLIFT_OUT` routed to `CHG-00067`) ride
 * inside the parent Handling line on the customer's invoice rather than
 * surfacing as a separate row.
 *
 * Rule:
 *  1. Group by `chargeTypeId`.
 *  2. If a group contains no SERVICE-source items, emit every line as-is —
 *     this preserves per-project storage breakdown, the storage minimum
 *     top-up line, and per-project movement breakdown.
 *  3. If a group contains SERVICE items but no non-SERVICE anchor, emit
 *     the SERVICE items as-is. This is the rare "forklift logged but no
 *     outbound that month" edge case (description leak is acceptable).
 *  4. Otherwise: pick the anchor (highest-amount non-SERVICE item), fold
 *     the SERVICE amounts into it by recomputing
 *     `unitPrice = (anchorAmount + sum(serviceAmounts)) / anchor.quantity`.
 *     Other non-SERVICE items in the group are emitted unchanged so
 *     per-project bifurcation survives.
 *  5. Defensive: if the anchor has zero quantity (shouldn't happen, but
 *     possible if a movement aggregator returned 0 CBM), fall back to
 *     emitting every item as-is rather than dividing by zero.
 *
 * Output ordering is by the surviving item's `originalIndex`, so the
 * FreighAi invoice line order stays stable run-over-run.
 *
 * Internal state — `WmsBillingInvoice` and `BillingRunCostSnapshot` keep
 * full bifurcation; consolidation is a presentation transform applied only
 * to the FreighAi outbound payload.
 */
internal fun consolidateFreighAiLines(
    tagged: List<TaggedFreighAiLine>
): List<FreighAiInvoiceLineItem> {
    if (tagged.isEmpty()) return emptyList()

    val grouped = LinkedHashMap<String, MutableList<TaggedFreighAiLine>>()
    for (t in tagged) {
        grouped.getOrPut(t.item.chargeTypeId) { mutableListOf() }.add(t)
    }

    data class Positioned(val orderIndex: Int, val item: FreighAiInvoiceLineItem)
    val out = mutableListOf<Positioned>()

    for ((_, group) in grouped) {
        val services = group.filter { it.source == FreighAiLineSource.SERVICE }
        val nonServices = group.filter { it.source != FreighAiLineSource.SERVICE }

        if (services.isEmpty() || nonServices.isEmpty()) {
            for (t in group) out += Positioned(t.originalIndex, t.item)
            continue
        }

        val anchor = nonServices.maxByOrNull { it.item.quantity.multiply(it.item.unitPrice) }!!
        if (anchor.item.quantity.signum() <= 0) {
            for (t in group) out += Positioned(t.originalIndex, t.item)
            continue
        }

        val anchorAmount = anchor.item.quantity.multiply(anchor.item.unitPrice)
        val serviceTotal = services.fold(BigDecimal.ZERO) { acc, t ->
            acc.add(t.item.quantity.multiply(t.item.unitPrice))
        }
        val newAmount = anchorAmount.add(serviceTotal).setScale(2, RoundingMode.HALF_UP)
        val newUnitPrice = newAmount.divide(anchor.item.quantity, 2, RoundingMode.HALF_UP)

        for (t in nonServices) {
            val item = if (t === anchor) anchor.item.copy(unitPrice = newUnitPrice) else t.item
            out += Positioned(t.originalIndex, item)
        }
    }

    return out.sortedBy { it.orderIndex }.map { it.item }
}
