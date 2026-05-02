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
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * OccupancyAggregator — CBM-day occupancy per (customer, projectCode) for a
 * given month. Reads `storage_items` and `quantity_based_inventory` collections
 * directly via MongoTemplate (both collections live in the same tenant DB, so
 * a Feign hop into Inventory Service would only add latency).
 *
 * **Heuristics for IN/OUT dates** (kept conservative):
 *   - StorageItem inDate  = max(monthStart, agingInfo.firstReceivedDate ?? createdAt)
 *   - StorageItem outDate = if currentStatus is "shipped/consumed": updatedAt; else: monthEnd+1
 *   - QBI inDate          = max(monthStart, createdAt)
 *   - QBI outDate         = if availableQuantity == 0: updatedAt; else: monthEnd+1
 *
 * The shipped-status detection looks for `currentStatus IN [SHIPPED, USED, ARCHIVED, RETURNED]`.
 * Storage items currently in any other state are treated as "still in warehouse".
 *
 * Result is grouped by `projectCode` (null = "Unassigned" → bills at customer
 * default rate). CBM-days is rounded to 4 decimal places at aggregation time.
 *
 * Known limitations (acceptable for v1):
 *   - For QBI, totalQuantity is treated as constant for the period — partial
 *     shipouts mid-month over-attribute. Acceptable approximation; for pure
 *     accuracy we'd integrate `quantity_transactions` over time.
 *   - Items with missing dimensions contribute zero — flagged by the data
 *     quality scan in Phase 6, not silently dropped.
 */
@Component
class OccupancyAggregator(
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val storageShippedStatuses = setOf("SHIPPED", "USED", "ARCHIVED", "RETURNED")

    /**
     * @return Map of `projectCode → cbm-days` (BigDecimal, 4dp). Key is
     *         null when items had no projectCode tag.
     */
    fun aggregate(customerId: Long, billingMonth: YearMonth): OccupancyResult {
        val monthStart = billingMonth.atDay(1)
        val monthEndExclusive = billingMonth.plusMonths(1).atDay(1)
        val monthStartTs = toEpochMillis(monthStart.atStartOfDay())
        val monthEndExclusiveTs = toEpochMillis(monthEndExclusive.atStartOfDay())

        val storageBuckets = mutableMapOf<String?, BigDecimal>()
        val qbiBuckets = mutableMapOf<String?, BigDecimal>()
        // Phase B: per-source-item breakdown for cost snapshot writes.
        val contributionsByProject = mutableMapOf<String?, MutableList<OccupancyContribution>>()
        val warnings = mutableListOf<OccupancyWarning>()

        // ── Storage items ─────────────────────────────────────────────
        // Filter: accountId match, createdAt < monthEndExclusive, AND
        //   (currentStatus NOT shipped) OR (updatedAt >= monthStart).
        // The OR clause keeps items that were shipped *during* the month.
        val storageQuery = Query(
            Criteria().andOperator(
                Criteria.where("accountId").`is`(customerId),
                Criteria.where("createdAt").lt(Date(monthEndExclusiveTs)),
                Criteria().orOperator(
                    Criteria.where("currentStatus").nin(storageShippedStatuses),
                    Criteria.where("updatedAt").gte(Date(monthStartTs))
                )
            )
        )
        val storageItems = mongoTemplate.find(storageQuery, Document::class.java, "storage_items")
        for (item in storageItems) {
            val cbm = computeStorageItemCbm(item)
            if (cbm == null || cbm.signum() == 0) {
                warnings += OccupancyWarning(
                    code = "STORAGE_ITEM_NO_DIMENSIONS",
                    affectedId = item.getString("_id") ?: "?"
                )
                continue
            }
            val inDate = computeStorageInDate(item, monthStart) ?: continue
            val outDate = computeStorageOutDate(item, monthEndExclusive)
            val daysPresent = daysBetween(inDate, outDate)
            if (daysPresent <= 0) continue

            val projectCode = (item["projectCode"] as? String)?.takeIf { it.isNotBlank() }
            val contribution = cbm.multiply(BigDecimal(daysPresent))
            storageBuckets.merge(projectCode, contribution, BigDecimal::add)
            contributionsByProject.getOrPut(projectCode) { mutableListOf() }.add(
                OccupancyContribution(
                    sourceId = item.getString("_id") ?: continue,
                    kind = OccupancyContributionKind.STORAGE_ITEM,
                    cbmDays = contribution,
                    projectCode = projectCode
                )
            )
        }

        // ── Quantity-based inventory ──────────────────────────────────
        val qbiQuery = Query(
            Criteria().andOperator(
                Criteria.where("accountId").`is`(customerId),
                Criteria.where("createdAt").lt(Date(monthEndExclusiveTs)),
                Criteria().orOperator(
                    Criteria.where("availableQuantity").gt(0),
                    Criteria.where("updatedAt").gte(Date(monthStartTs))
                )
            )
        )
        val qbiItems = mongoTemplate.find(qbiQuery, Document::class.java, "quantity_based_inventory")
        for (item in qbiItems) {
            val cbmPerUnit = computeQbiCbmPerUnit(item)
            val totalQty = (item["totalQuantity"] as? Number)?.toInt() ?: 0
            if (cbmPerUnit == null || cbmPerUnit.signum() == 0 || totalQty == 0) {
                if (totalQty > 0) {
                    warnings += OccupancyWarning(
                        code = "QBI_NO_DIMENSIONS",
                        affectedId = item.getString("_id") ?: "?"
                    )
                }
                continue
            }
            val inDate = computeQbiInDate(item, monthStart) ?: continue
            val outDate = computeQbiOutDate(item, monthEndExclusive)
            val daysPresent = daysBetween(inDate, outDate)
            if (daysPresent <= 0) continue

            val projectCode = (item["projectCode"] as? String)?.takeIf { it.isNotBlank() }
            val contribution = cbmPerUnit
                .multiply(BigDecimal(totalQty))
                .multiply(BigDecimal(daysPresent))
            qbiBuckets.merge(projectCode, contribution, BigDecimal::add)
            contributionsByProject.getOrPut(projectCode) { mutableListOf() }.add(
                OccupancyContribution(
                    sourceId = item.getString("_id") ?: continue,
                    kind = OccupancyContributionKind.QUANTITY_INVENTORY,
                    cbmDays = contribution,
                    projectCode = projectCode
                )
            )
        }

        // Combine & round.
        val combined = mutableMapOf<String?, BigDecimal>()
        for ((k, v) in storageBuckets) combined.merge(k, v, BigDecimal::add)
        for ((k, v) in qbiBuckets) combined.merge(k, v, BigDecimal::add)
        val rounded = combined.mapValues { (_, v) -> v.setScale(4, RoundingMode.HALF_UP) }

        logger.debug(
            "Occupancy aggregation customerId={} month={} → buckets={} warnings={}",
            customerId, billingMonth, rounded.size, warnings.size
        )
        return OccupancyResult(
            cbmDaysByProject = rounded,
            warnings = warnings,
            contributionsByProject = contributionsByProject
        )
    }

    // ── helpers ─────────────────────────────────────────────────────

    private fun computeStorageItemCbm(doc: Document): BigDecimal? {
        // Prefer pre-computed `dimensions.cbm` if present; otherwise compute.
        val dims = doc["dimensions"] as? Document ?: return null
        val cached = dims["cbm"] as? Number
        if (cached != null) return BigDecimal(cached.toString())
        return computeCbmFromDimensions(dims)
    }

    private fun computeQbiCbmPerUnit(doc: Document): BigDecimal? {
        val dims = doc["dimensions"] as? Document ?: return null
        val cached = dims["cbm"] as? Number
        if (cached != null) return BigDecimal(cached.toString())
        return computeCbmFromDimensions(dims)
    }

    private fun computeCbmFromDimensions(dims: Document): BigDecimal? {
        val l = (dims["lengthCm"] as? Number)?.toDouble() ?: return null
        val w = (dims["widthCm"] as? Number)?.toDouble() ?: return null
        val h = (dims["heightCm"] as? Number)?.toDouble() ?: return null
        return BigDecimal((l * w * h) / 1_000_000.0)
            .setScale(6, RoundingMode.HALF_UP)
    }

    private fun computeStorageInDate(doc: Document, monthStart: LocalDate): LocalDate? {
        val aging = doc["agingInfo"] as? Document
        val firstReceived = (aging?.get("firstReceivedDate") as? Date)?.let { dateToLocalDate(it) }
        val createdAt = (doc["createdAt"] as? Date)?.let { dateToLocalDate(it) }
        val effective = firstReceived ?: createdAt ?: return null
        return if (effective.isAfter(monthStart)) effective else monthStart
    }

    private fun computeStorageOutDate(doc: Document, monthEndExclusive: LocalDate): LocalDate {
        val status = doc["currentStatus"] as? String
        return if (status in storageShippedStatuses) {
            val updatedAt = (doc["updatedAt"] as? Date)?.let { dateToLocalDate(it) }
            // Cap at monthEnd — items shipped after month-end shouldn't reduce occupancy this month.
            updatedAt?.takeIf { it.isBefore(monthEndExclusive) } ?: monthEndExclusive
        } else {
            monthEndExclusive
        }
    }

    private fun computeQbiInDate(doc: Document, monthStart: LocalDate): LocalDate? {
        val createdAt = (doc["createdAt"] as? Date)?.let { dateToLocalDate(it) } ?: return null
        return if (createdAt.isAfter(monthStart)) createdAt else monthStart
    }

    private fun computeQbiOutDate(doc: Document, monthEndExclusive: LocalDate): LocalDate {
        val avail = (doc["availableQuantity"] as? Number)?.toInt() ?: 0
        return if (avail == 0) {
            val updatedAt = (doc["updatedAt"] as? Date)?.let { dateToLocalDate(it) }
            updatedAt?.takeIf { it.isBefore(monthEndExclusive) } ?: monthEndExclusive
        } else {
            monthEndExclusive
        }
    }

    private fun daysBetween(inDate: LocalDate, outDateExclusive: LocalDate): Long =
        if (outDateExclusive.isAfter(inDate)) {
            ChronoUnit.DAYS.between(inDate, outDateExclusive)
        } else 0

    private fun dateToLocalDate(d: Date): LocalDate =
        d.toInstant().atOffset(ZoneOffset.UTC).toLocalDate()

    private fun toEpochMillis(ldt: LocalDateTime): Long =
        ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
}

data class OccupancyResult(
    val cbmDaysByProject: Map<String?, BigDecimal>,
    val warnings: List<OccupancyWarning>,
    /**
     * Phase B: per-storage-item / per-QBI contribution breakdown grouped by
     * project. Used by BillingRunService to write one cost snapshot per
     * source item per billing run. Each entry represents one storage_item
     * or one quantity_based_inventory row's CBM-day contribution to the
     * project bucket.
     */
    val contributionsByProject: Map<String?, List<OccupancyContribution>> = emptyMap()
)

data class OccupancyContribution(
    val sourceId: String,
    val kind: OccupancyContributionKind,
    val cbmDays: BigDecimal,
    val projectCode: String?
)

enum class OccupancyContributionKind { STORAGE_ITEM, QUANTITY_INVENTORY }

data class OccupancyWarning(
    val code: String,
    val affectedId: String
)
