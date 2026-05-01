package com.wmspro.tenant.billing.invoice.aggregator

import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.Date

/**
 * MovementAggregator — inbound/outbound CBM totals per (customer, projectCode)
 * for a given month, derived from receiving_records and order_fulfillment_requests
 * respectively.
 *
 * **Inbound completion date** = first statusHistory entry whose `status` is
 * one of [RECEIVING_DONE, PUT_AWAY_DONE, GRN_SENT]. The earliest such
 * timestamp determines the billing month.
 *
 * **Outbound shipped date** = first statusHistory entry whose `status` is
 * SHIPPED. Earliest such timestamp determines the billing month.
 *
 * **CBM derivation:**
 *   - Inbound RR CBM = sum of CBMs across:
 *       - StorageItems whose IDs appear in `receivedItems[].createdStorageItems`
 *       - QBIs whose IDs appear in `receivedItems[].createdQuantityInventoryIds`,
 *         multiplied by `totalQuantity`
 *   - Outbound OFR CBM = sum of CBMs across `packages[].dimensions`. If no
 *     packages exist (e.g., shipped via direct line items without packaging),
 *     falls back to lineItems × resolved CBM (best-effort).
 *
 * Records already locked to a billing invoice (`billingInvoiceId != null`)
 * are excluded — Phase 5's billing engine respects existing locks.
 */
@Component
class MovementAggregator(
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val inboundCompletionStatuses = setOf("RECEIVING_DONE", "PUT_AWAY_DONE", "GRN_SENT")
    private val outboundShippedStatuses = setOf("SHIPPED", "DELIVERED")

    fun aggregateInbound(customerId: Long, billingMonth: YearMonth): InboundMovementResult {
        val monthStart = billingMonth.atDay(1)
        val monthEndExclusive = billingMonth.plusMonths(1).atDay(1)
        val warnings = mutableListOf<MovementWarning>()
        val byProject = mutableMapOf<String?, MovementBucket>()

        // Find unbilled receiving_records with any completion status. Filter by
        // statusHistory completion timestamp in JVM (cleaner than constructing
        // a complex aggregation pipeline).
        val rrQuery = Query(
            Criteria().andOperator(
                Criteria.where("accountId").`is`(customerId),
                Criteria.where("billingInvoiceId").`is`(null)
            )
        )
        val rrs = mongoTemplate.find(rrQuery, Document::class.java, "receiving_records")
        for (rr in rrs) {
            val completionTs = earliestStatusTimestamp(rr, inboundCompletionStatuses) ?: continue
            val completionDate = completionTs.toLocalDate()
            if (completionDate.isBefore(monthStart) || !completionDate.isBefore(monthEndExclusive)) continue

            val rrId = rr.getString("_id") ?: continue
            val projectCode = (rr["projectCode"] as? String)?.takeIf { it.isNotBlank() }

            val (cbm, hadDimensionGap) = computeInboundCbm(rr)
            if (hadDimensionGap) {
                warnings += MovementWarning(
                    code = "INBOUND_PARTIAL_DIMENSIONS",
                    recordId = rrId
                )
            }
            if (cbm.signum() == 0) continue

            byProject.compute(projectCode) { _, existing ->
                if (existing == null) {
                    MovementBucket(totalCbm = cbm, sourceRecordIds = mutableListOf(rrId))
                } else {
                    MovementBucket(
                        totalCbm = existing.totalCbm.add(cbm),
                        sourceRecordIds = (existing.sourceRecordIds + rrId).toMutableList()
                    )
                }
            }
        }

        val rounded = byProject.mapValues { (_, b) ->
            b.copy(totalCbm = b.totalCbm.setScale(4, RoundingMode.HALF_UP))
        }
        logger.debug(
            "Inbound movement aggregation customerId={} month={} → buckets={} warnings={}",
            customerId, billingMonth, rounded.size, warnings.size
        )
        return InboundMovementResult(rounded, warnings)
    }

    fun aggregateOutbound(customerId: Long, billingMonth: YearMonth): OutboundMovementResult {
        val monthStart = billingMonth.atDay(1)
        val monthEndExclusive = billingMonth.plusMonths(1).atDay(1)
        val warnings = mutableListOf<MovementWarning>()
        val byProject = mutableMapOf<String?, MovementBucket>()

        val ofrQuery = Query(
            Criteria().andOperator(
                Criteria.where("accountId").`is`(customerId),
                Criteria.where("billingInvoiceId").`is`(null)
            )
        )
        val ofrs = mongoTemplate.find(ofrQuery, Document::class.java, "order_fulfillment_requests")
        for (ofr in ofrs) {
            val shippedTs = earliestStatusTimestamp(ofr, outboundShippedStatuses) ?: continue
            val shippedDate = shippedTs.toLocalDate()
            if (shippedDate.isBefore(monthStart) || !shippedDate.isBefore(monthEndExclusive)) continue

            val ofrId = ofr.getString("_id") ?: continue
            val projectCode = (ofr["projectCode"] as? String)?.takeIf { it.isNotBlank() }

            val (cbm, hadDimensionGap) = computeOutboundCbm(ofr)
            if (hadDimensionGap) {
                warnings += MovementWarning(
                    code = "OUTBOUND_PARTIAL_DIMENSIONS",
                    recordId = ofrId
                )
            }
            if (cbm.signum() == 0) continue

            byProject.compute(projectCode) { _, existing ->
                if (existing == null) {
                    MovementBucket(totalCbm = cbm, sourceRecordIds = mutableListOf(ofrId))
                } else {
                    MovementBucket(
                        totalCbm = existing.totalCbm.add(cbm),
                        sourceRecordIds = (existing.sourceRecordIds + ofrId).toMutableList()
                    )
                }
            }
        }

        val rounded = byProject.mapValues { (_, b) ->
            b.copy(totalCbm = b.totalCbm.setScale(4, RoundingMode.HALF_UP))
        }
        return OutboundMovementResult(rounded, warnings)
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Inspects `statusHistory[]` for the earliest entry matching one of the
     * desired statuses. RR uses `changedAt`, OFR uses `timestamp` — handle both.
     */
    @Suppress("UNCHECKED_CAST")
    private fun earliestStatusTimestamp(doc: Document, statuses: Set<String>): LocalDateTime? {
        val history = doc["statusHistory"] as? List<Document> ?: return null
        return history
            .filter { (it["status"] as? String) in statuses }
            .mapNotNull { (it["changedAt"] as? Date) ?: (it["timestamp"] as? Date) }
            .minOrNull()
            ?.toInstant()
            ?.atOffset(ZoneOffset.UTC)
            ?.toLocalDateTime()
    }

    /**
     * Sum CBM from StorageItems and QBIs that this RR created. Returns
     * (totalCbm, hadDimensionGap) where the second flag indicates whether
     * any item / QBI we tried to count was missing dimensions — Phase 6's
     * data-quality scan uses that to surface a warning row.
     */
    @Suppress("UNCHECKED_CAST")
    private fun computeInboundCbm(rr: Document): Pair<BigDecimal, Boolean> {
        val received = rr["receivedItems"] as? List<Document> ?: return BigDecimal.ZERO to false
        val storageItemIds = mutableListOf<Long>()
        val qbiIds = mutableListOf<String>()
        received.forEach { item ->
            (item["createdStorageItems"] as? List<Number>)?.forEach { storageItemIds += it.toLong() }
            (item["createdQuantityInventoryIds"] as? List<String>)?.forEach { qbiIds += it }
        }

        var total = BigDecimal.ZERO
        var hadGap = false

        if (storageItemIds.isNotEmpty()) {
            val items = mongoTemplate.find(
                Query(Criteria.where("_id").`in`(storageItemIds)),
                Document::class.java,
                "storage_items"
            )
            for (it in items) {
                val cbm = computeCbmOfDoc(it["dimensions"] as? Document)
                if (cbm == null) hadGap = true
                else total = total.add(cbm)
            }
        }

        if (qbiIds.isNotEmpty()) {
            val qbis = mongoTemplate.find(
                Query(Criteria.where("_id").`in`(qbiIds)),
                Document::class.java,
                "quantity_based_inventory"
            )
            for (qbi in qbis) {
                val cbmPerUnit = computeCbmOfDoc(qbi["dimensions"] as? Document)
                val qty = (qbi["totalQuantity"] as? Number)?.toInt() ?: 0
                if (cbmPerUnit == null && qty > 0) hadGap = true
                else if (cbmPerUnit != null && qty > 0) {
                    total = total.add(cbmPerUnit.multiply(BigDecimal(qty)))
                }
            }
        }

        return total to hadGap
    }

    /**
     * Outbound CBM: sum package dimensions if available; for fulfillments
     * with no packages, returns 0 with hadGap=true so the data-quality scan
     * surfaces the issue.
     */
    @Suppress("UNCHECKED_CAST")
    private fun computeOutboundCbm(ofr: Document): Pair<BigDecimal, Boolean> {
        val packages = ofr["packages"] as? List<Document> ?: emptyList()
        if (packages.isEmpty()) return BigDecimal.ZERO to true

        var total = BigDecimal.ZERO
        var hadGap = false
        for (pkg in packages) {
            val dims = pkg["dimensions"] as? Document
            val cbm = computeCbmOfPackageDoc(dims)
            if (cbm == null) hadGap = true
            else total = total.add(cbm)
        }
        return total to hadGap
    }

    private fun computeCbmOfDoc(dims: Document?): BigDecimal? {
        if (dims == null) return null
        val cached = dims["cbm"] as? Number
        if (cached != null) return BigDecimal(cached.toString())
        val l = (dims["lengthCm"] as? Number)?.toDouble() ?: return null
        val w = (dims["widthCm"] as? Number)?.toDouble() ?: return null
        val h = (dims["heightCm"] as? Number)?.toDouble() ?: return null
        return BigDecimal((l * w * h) / 1_000_000.0).setScale(6, RoundingMode.HALF_UP)
    }

    /**
     * Package dimensions use unit-aware fields (`length`, `width`, `height`,
     * `unit` per `PackageDimensions`). Default unit is "cm".
     */
    private fun computeCbmOfPackageDoc(dims: Document?): BigDecimal? {
        if (dims == null) return null
        val l = (dims["length"] as? Number)?.toDouble() ?: return null
        val w = (dims["width"] as? Number)?.toDouble() ?: return null
        val h = (dims["height"] as? Number)?.toDouble() ?: return null
        val unit = (dims["unit"] as? String) ?: "cm"
        val divisor = if (unit.equals("m", ignoreCase = true)) 1.0 else 1_000_000.0
        return BigDecimal((l * w * h) / divisor).setScale(6, RoundingMode.HALF_UP)
    }
}

// ── result types ────────────────────────────────────────────────────

data class InboundMovementResult(
    val byProject: Map<String?, MovementBucket>,
    val warnings: List<MovementWarning>
)

data class OutboundMovementResult(
    val byProject: Map<String?, MovementBucket>,
    val warnings: List<MovementWarning>
)

data class MovementBucket(
    val totalCbm: BigDecimal,
    val sourceRecordIds: MutableList<String>
)

data class MovementWarning(
    val code: String,
    val recordId: String
)
