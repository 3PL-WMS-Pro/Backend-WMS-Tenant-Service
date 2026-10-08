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
 * given month, optionally split by warehouse. Reads `storage_items` and
 * `quantity_based_inventory` collections directly via MongoTemplate (both
 * collections live in the same tenant DB, so a Feign hop into Inventory
 * Service would only add latency).
 *
 * Two sets of counting rules, chosen by billing month (see [CountingRules]):
 *
 * **LEGACY** (months before the cut-over — reproduces every invoice already issued):
 *   - every storage item with a `createdAt`, including items inside pallets and boxes
 *   - StorageItem inDate  = max(monthStart, agingInfo.firstReceivedDate ?? createdAt)
 *   - StorageItem outDate = if currentStatus is "shipped/consumed": updatedAt; else: monthEnd+1
 *   - QBI inDate          = max(monthStart, createdAt)
 *   - QBI outDate         = if availableQuantity == 0: updatedAt; else: monthEnd+1
 *
 * **PALLET_WISE** (the cut-over month onward — matches the CBM report and portal):
 *   - only top-level stock: a pallet counts at its own size and the items on it add nothing;
 *     a loose item counts at its own size. Infinity bills storage by the pallet.
 *   - stock with no `createdAt` (migrated) counts from `agingInfo.firstReceivedDate`
 *   - a receipt counts from the GRN's actual receiving date when that is earlier than the booking
 *   - shipped stock stops counting the day it left: shipping moves an item off site and leaves its
 *     status AVAILABLE, so status alone kept it billable forever
 *   - an empty pallet or box no receipt created (minted for re-palletisation) does not count
 *   - QBI: top-level lots only, from the GRN's receiving date when earlier
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
 *   - PALLET_WISE reads containment as it stands today: an item palletised mid-month counts as
 *     part of its pallet for the whole month.
 */
@Component
class OccupancyAggregator(
    private val mongoTemplate: MongoTemplate,
    private val warehouseAttribution: WarehouseAttribution
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val storageShippedStatuses = setOf("SHIPPED", "USED", "ARCHIVED", "RETURNED")

    /**
     * @return one [OccupancyResult] per warehouse when [splitByWarehouse], keyed by warehouse id
     *         (null = stock whose warehouse could not be determined); otherwise a single result
     *         under the null key. Within a result, `cbmDaysByProject` maps `projectCode → cbm-days`
     *         (BigDecimal, 4dp); the key is null when items had no projectCode tag.
     */
    fun aggregate(
        customerId: Long,
        billingMonth: YearMonth,
        rules: CountingRules = CountingRules.LEGACY,
        splitByWarehouse: Boolean = false
    ): Map<String?, OccupancyResult> {
        val monthStart = billingMonth.atDay(1)
        val monthEndExclusive = billingMonth.plusMonths(1).atDay(1)
        val warehouseIds = if (splitByWarehouse) warehouseAttribution.warehouseNames().keys else emptySet()

        val pieces = mutableListOf<OccupancyPiece>()
        val warnings = mutableListOf<Pair<String?, OccupancyWarning>>()
        val receipts = if (rules == CountingRules.PALLET_WISE || splitByWarehouse) receiptsFor(customerId) else Receipts.NONE

        collectStorageItems(customerId, monthStart, monthEndExclusive, rules, splitByWarehouse, warehouseIds, receipts, pieces, warnings)
        collectQuantityLots(customerId, monthStart, monthEndExclusive, rules, splitByWarehouse, warehouseIds, receipts, pieces, warnings)

        // Bucket each piece's CBM-days into the warehouse it sat in on each of its days.
        val byWarehouse = mutableMapOf<String?, Buckets>()
        if (!splitByWarehouse) byWarehouse[null] = Buckets()
        for (piece in pieces) {
            for (segment in piece.segments) {
                val days = daysBetween(maxOf(piece.inDate, segment.from), minOf(piece.outDateExclusive, segment.toExclusive))
                if (days <= 0) continue
                val contribution = piece.cbm.multiply(BigDecimal(days))
                byWarehouse.getOrPut(segment.warehouseId) { Buckets() }.add(piece, contribution)
            }
        }
        for ((warehouseId, warning) in warnings) byWarehouse.getOrPut(warehouseId) { Buckets() }.warnings += warning

        logger.debug(
            "Occupancy aggregation customerId={} month={} rules={} → warehouses={} pieces={} warnings={}",
            customerId, billingMonth, rules, byWarehouse.keys, pieces.size, warnings.size
        )
        return byWarehouse.mapValues { (_, buckets) -> buckets.toResult() }
    }

    // ── Storage items ─────────────────────────────────────────────────

    private fun collectStorageItems(
        customerId: Long,
        monthStart: LocalDate,
        monthEndExclusive: LocalDate,
        rules: CountingRules,
        splitByWarehouse: Boolean,
        warehouseIds: Set<String>,
        receipts: Receipts,
        pieces: MutableList<OccupancyPiece>,
        warnings: MutableList<Pair<String?, OccupancyWarning>>
    ) {
        val monthStartDate = Date(toEpochMillis(monthStart.atStartOfDay()))
        val monthEndDate = Date(toEpochMillis(monthEndExclusive.atStartOfDay()))

        val storageQuery = when (rules) {
            // Filter: accountId match, createdAt < monthEndExclusive, AND
            //   (currentStatus NOT shipped) OR (updatedAt >= monthStart).
            // The OR clause keeps items that were shipped *during* the month.
            CountingRules.LEGACY -> Query(
                Criteria().andOperator(
                    Criteria.where("accountId").`is`(customerId),
                    Criteria.where("createdAt").lt(monthEndDate),
                    Criteria().orOperator(
                        Criteria.where("currentStatus").nin(storageShippedStatuses),
                        Criteria.where("updatedAt").gte(monthStartDate)
                    )
                )
            )
            // Top-level stock, dated by creation or - for migrated stock - first receipt. Whether
            // it had already left is decided per item below: shipped stock keeps status AVAILABLE.
            CountingRules.PALLET_WISE -> Query(
                Criteria().andOperator(
                    Criteria.where("accountId").`is`(customerId),
                    Criteria.where("parentItemId").`is`(null),
                    Criteria().orOperator(
                        Criteria.where("createdAt").lt(monthEndDate),
                        Criteria.where("agingInfo.firstReceivedDate").lt(monthEndDate)
                    )
                )
            )
        }
        val storageItems = mongoTemplate.find(storageQuery, Document::class.java, "storage_items")
            .filter { rules == CountingRules.LEGACY || !isEmptyStandaloneContainer(it, receipts) }

        // One read of the audit trail serves both departures (pallet-wise rules) and the
        // warehouse each item sat in during the month (per-warehouse invoicing).
        val itemIdsNeedingMoves = storageItems.filter { item ->
            splitByWarehouse || (rules == CountingRules.PALLET_WISE && isOffsite(item))
        }.map { (it["_id"] as Number).toLong() }
        val movesByItem = movesSince(itemIdsNeedingMoves, monthStartDate)

        for (item in storageItems) {
            val itemId = (item["_id"] as Number).toLong()
            val currentWarehouse = warehouseAttribution.warehouseOf(locationCodeOf(item), warehouseIds)
                ?: receipts.byStorageItem[itemId]?.warehouseId
            val cbm = computeStorageItemCbm(item)
            if (cbm == null || cbm.signum() == 0) {
                warnings += currentWarehouse to OccupancyWarning(
                    code = "STORAGE_ITEM_NO_DIMENSIONS",
                    affectedId = item.idString() ?: "?"
                )
                continue
            }
            val inDate = when (rules) {
                CountingRules.LEGACY -> computeStorageInDate(item, monthStart)
                CountingRules.PALLET_WISE -> computeArrivalInDate(item, receipts.byStorageItem[itemId]?.receivedDate, monthStart)
            } ?: continue
            val outDate = when (rules) {
                CountingRules.LEGACY -> computeStorageOutDate(item, monthEndExclusive)
                CountingRules.PALLET_WISE -> computeDepartureOutDate(item, movesByItem[itemId].orEmpty(), monthEndExclusive)
            }
            if (daysBetween(inDate, outDate) <= 0) continue

            val segments = if (splitByWarehouse) {
                warehouseSegments(item, movesByItem[itemId].orEmpty(), monthStart, monthEndExclusive, warehouseIds,
                    fallbackWarehouse = receipts.byStorageItem[itemId]?.warehouseId)
            } else {
                listOf(WarehouseSegment(null, monthStart, monthEndExclusive))
            }
            pieces += OccupancyPiece(
                sourceId = item.idString() ?: continue,
                kind = OccupancyContributionKind.STORAGE_ITEM,
                projectCode = (item["projectCode"] as? String)?.takeIf { it.isNotBlank() },
                cbm = cbm,
                inDate = inDate,
                outDateExclusive = outDate,
                segments = segments
            )
        }
    }

    // ── Quantity-based inventory ──────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun collectQuantityLots(
        customerId: Long,
        monthStart: LocalDate,
        monthEndExclusive: LocalDate,
        rules: CountingRules,
        splitByWarehouse: Boolean,
        warehouseIds: Set<String>,
        receipts: Receipts,
        pieces: MutableList<OccupancyPiece>,
        warnings: MutableList<Pair<String?, OccupancyWarning>>
    ) {
        val criteria = mutableListOf(
            Criteria.where("accountId").`is`(customerId),
            Criteria.where("createdAt").lt(Date(toEpochMillis(monthEndExclusive.atStartOfDay()))),
            Criteria().orOperator(
                Criteria.where("availableQuantity").gt(0),
                Criteria.where("updatedAt").gte(Date(toEpochMillis(monthStart.atStartOfDay())))
            )
        )
        // Pallet-wise: a lot inside a container is part of that container.
        if (rules == CountingRules.PALLET_WISE) criteria += Criteria.where("parentContainerId").`is`(null)
        val qbiItems = mongoTemplate.find(Query(Criteria().andOperator(criteria)), Document::class.java, "quantity_based_inventory")

        for (item in qbiItems) {
            val receivingRecordId = item["receivingRecordId"] as? String
            val lotWarehouse = if (splitByWarehouse) {
                (item["locationAllocations"] as? List<Document>).orEmpty()
                    .groupBy { warehouseAttribution.warehouseOf(it["locationCode"] as? String, warehouseIds) }
                    .filterKeys { it != null }
                    .maxByOrNull { (_, allocations) -> allocations.sumOf { (it["quantity"] as? Number)?.toInt() ?: 0 } }
                    ?.key
                    ?: receivingRecordId?.let { receipts.byReceivingRecord[it]?.warehouseId }
            } else null

            val cbmPerUnit = computeQbiCbmPerUnit(item)
            val totalQty = (item["totalQuantity"] as? Number)?.toInt() ?: 0
            if (cbmPerUnit == null || cbmPerUnit.signum() == 0 || totalQty == 0) {
                if (totalQty > 0) {
                    warnings += lotWarehouse to OccupancyWarning(
                        code = "QBI_NO_DIMENSIONS",
                        affectedId = item.idString() ?: "?"
                    )
                }
                continue
            }
            val inDate = when (rules) {
                CountingRules.LEGACY -> computeQbiInDate(item, monthStart)
                CountingRules.PALLET_WISE -> computeArrivalInDate(
                    item, receivingRecordId?.let { receipts.byReceivingRecord[it]?.receivedDate }, monthStart
                )
            } ?: continue
            val outDate = computeQbiOutDate(item, monthEndExclusive)
            if (daysBetween(inDate, outDate) <= 0) continue

            pieces += OccupancyPiece(
                sourceId = item.idString() ?: continue,
                kind = OccupancyContributionKind.QUANTITY_INVENTORY,
                projectCode = (item["projectCode"] as? String)?.takeIf { it.isNotBlank() },
                cbm = cbmPerUnit.multiply(BigDecimal(totalQty)),
                inDate = inDate,
                outDateExclusive = outDate,
                segments = listOf(WarehouseSegment(lotWarehouse, monthStart, monthEndExclusive))
            )
        }
    }

    // ── Pallet-wise and per-warehouse helpers ─────────────────────────

    /** Each receipt's actual receiving date and warehouse, by the storage items and by the record. */
    @Suppress("UNCHECKED_CAST")
    private fun receiptsFor(customerId: Long): Receipts {
        val records = mongoTemplate.find(
            Query(Criteria.where("accountId").`is`(customerId)).apply {
                fields().include("receivedDate").include("warehouseId").include("receivedItems.createdStorageItems")
            },
            Document::class.java,
            "receiving_records"
        )
        val byRecord = mutableMapOf<String, Receipt>()
        val byItem = mutableMapOf<Long, Receipt>()
        for (record in records) {
            val receipt = Receipt(
                receivedDate = record["receivedDate"] as? Date,
                warehouseId = (record["warehouseId"] as? String)?.takeIf { it.isNotBlank() }
            )
            byRecord[record["_id"].toString()] = receipt
            (record["receivedItems"] as? List<Document>).orEmpty()
                .flatMap { (it["createdStorageItems"] as? List<Number>).orEmpty() }
                .forEach { byItem[it.toLong()] = receipt }
        }
        return Receipts(byItem, byRecord)
    }

    /**
     * A pallet or box holding nothing that no receipt created: minted empty to rebuild structure
     * for stock already in the warehouse. It holds none of the customer's goods yet. An empty
     * container a receipt did create still counts - in container tracking it is the goods.
     */
    @Suppress("UNCHECKED_CAST")
    private fun isEmptyStandaloneContainer(item: Document, receipts: Receipts): Boolean {
        if (item["itemType"] == "SKU_ITEM") return false
        if ((item["childItems"] as? List<Any>).orEmpty().isNotEmpty()) return false
        return (item["_id"] as Number).toLong() !in receipts.byStorageItem
    }

    /** Location and containment moves for the given items from [since] onward, oldest first. */
    private fun movesSince(storageItemIds: List<Long>, since: Date): Map<Long, List<Document>> {
        if (storageItemIds.isEmpty()) return emptyMap()
        return mongoTemplate.find(
            Query(
                Criteria().andOperator(
                    Criteria.where("storageItemId").`in`(storageItemIds),
                    Criteria.where("transactionType").`in`("LOCATION_CHANGE", "CONTAINMENT_CHANGE"),
                    Criteria.where("timestamp").gte(since)
                )
            ),
            Document::class.java,
            "item_transactions"
        ).groupBy { (it["storageItemId"] as Number).toLong() }
            .mapValues { (_, moves) -> moves.sortedBy { it["timestamp"] as? Date } }
    }

    /**
     * Where the item sat on each day of the month, as warehouse segments. Walks back from today's
     * location through every move since the month began; a location in no warehouse (receiving
     * dock, in transit) falls back to the warehouse of the receipt that created the item.
     */
    private fun warehouseSegments(
        item: Document,
        movesSinceMonthStart: List<Document>,
        monthStart: LocalDate,
        monthEndExclusive: LocalDate,
        warehouseIds: Set<String>,
        fallbackWarehouse: String?
    ): List<WarehouseSegment> {
        fun warehouseAt(location: String?) = warehouseAttribution.warehouseOf(location, warehouseIds) ?: fallbackWarehouse

        var location = locationCodeOf(item)
        val (afterMonth, inMonth) = movesSinceMonthStart.partition {
            !dateToLocalDate(it["timestamp"] as Date).isBefore(monthEndExclusive)
        }
        // Undo the moves made after the month to find where the item stood at its end.
        for (move in afterMonth.asReversed()) location = beforeLocationOf(move) ?: location

        val segments = mutableListOf<WarehouseSegment>()
        var segmentEnd = monthEndExclusive
        for (move in inMonth.asReversed()) {
            val movedOn = dateToLocalDate(move["timestamp"] as Date)
            segments += WarehouseSegment(warehouseAt(location), movedOn, segmentEnd)
            segmentEnd = movedOn
            location = beforeLocationOf(move) ?: location
        }
        segments += WarehouseSegment(warehouseAt(location), monthStart, segmentEnd)
        return segments
    }

    /**
     * When the goods arrived: the GRN's receiving date when it is earlier than the booking (receipts
     * are often booked weeks in arrears), else the booking. A receiving date after the booking is a
     * data-entry slip and is ignored.
     */
    private fun computeArrivalInDate(doc: Document, receivedDate: Date?, monthStart: LocalDate): LocalDate? {
        val aging = doc["agingInfo"] as? Document
        val booked = (aging?.get("firstReceivedDate") as? Date) ?: (doc["createdAt"] as? Date)
        val arrived = listOfNotNull(receivedDate, booked).minOrNull()?.let { dateToLocalDate(it) } ?: return null
        return if (arrived.isAfter(monthStart)) arrived else monthStart
    }

    /**
     * The day the item left, capped at month end. Off-site items left on their last move from on
     * site to off site - or, when that move predates the month, their latestMovement. An off-site
     * item with no record of when is treated as gone before the month began rather than billed.
     */
    private fun computeDepartureOutDate(item: Document, movesSinceMonthStart: List<Document>, monthEndExclusive: LocalDate): LocalDate {
        if ((item["currentStatus"] as? String) in storageShippedStatuses) {
            return computeStorageOutDate(item, monthEndExclusive)
        }
        if (!isOffsite(item)) return monthEndExclusive
        val departedAt = movesSinceMonthStart
            .filter {
                WarehouseAttribution.isOffsite(afterLocationOf(it)) && !WarehouseAttribution.isOffsite(beforeLocationOf(it))
            }
            .mapNotNull { it["timestamp"] as? Date }
            .maxOrNull()
            ?: ((item["latestMovement"] as? Document)?.get("timestamp") as? Date)
        val departedOn = departedAt?.let { dateToLocalDate(it) } ?: return LocalDate.MIN
        return if (departedOn.isBefore(monthEndExclusive)) departedOn else monthEndExclusive
    }

    private fun isOffsite(item: Document): Boolean = WarehouseAttribution.isOffsite(locationCodeOf(item))

    private fun locationCodeOf(item: Document): String? = (item["currentLocation"] as? Document)?.getString("locationCode")
    private fun beforeLocationOf(move: Document): String? = (move["beforeLocation"] as? Document)?.getString("locationCode")
    private fun afterLocationOf(move: Document): String? = (move["afterLocation"] as? Document)?.getString("locationCode")

    // ── helpers ─────────────────────────────────────────────────────

    /**
     * The document id as text. storage_items ids are numeric (Long) while quantity_based_inventory
     * ids are strings; `getString("_id")` threw ClassCastException on the first storage item, which
     * failed the whole billing run - preview included - for any customer with a dated storage item.
     */
    private fun Document.idString(): String? = this["_id"]?.toString()

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

    private data class Receipt(val receivedDate: Date?, val warehouseId: String?)

    private data class Receipts(val byStorageItem: Map<Long, Receipt>, val byReceivingRecord: Map<String, Receipt>) {
        companion object {
            val NONE = Receipts(emptyMap(), emptyMap())
        }
    }

    private data class WarehouseSegment(val warehouseId: String?, val from: LocalDate, val toExclusive: LocalDate)

    /** One storage item or QBI lot, with the days it was present and where it sat. */
    private data class OccupancyPiece(
        val sourceId: String,
        val kind: OccupancyContributionKind,
        val projectCode: String?,
        val cbm: BigDecimal,
        val inDate: LocalDate,
        val outDateExclusive: LocalDate,
        val segments: List<WarehouseSegment>
    )

    private class Buckets {
        val cbmDaysByProject = mutableMapOf<String?, BigDecimal>()
        val contributionsByProject = mutableMapOf<String?, MutableList<OccupancyContribution>>()
        val warnings = mutableListOf<OccupancyWarning>()

        fun add(piece: OccupancyPiece, contribution: BigDecimal) {
            cbmDaysByProject.merge(piece.projectCode, contribution, BigDecimal::add)
            contributionsByProject.getOrPut(piece.projectCode) { mutableListOf() }.add(
                OccupancyContribution(
                    sourceId = piece.sourceId,
                    kind = piece.kind,
                    cbmDays = contribution,
                    projectCode = piece.projectCode
                )
            )
        }

        fun toResult() = OccupancyResult(
            cbmDaysByProject = cbmDaysByProject.mapValues { (_, v) -> v.setScale(4, RoundingMode.HALF_UP) },
            warnings = warnings,
            contributionsByProject = contributionsByProject
        )
    }
}

/**
 * Which storage-counting rules a billing month uses. Invoices already issued were computed under
 * [LEGACY]; [PALLET_WISE] applies from the cut-over month onward, so re-running or previewing an
 * old month reproduces exactly what was billed.
 */
enum class CountingRules {
    LEGACY,
    PALLET_WISE;

    companion object {
        fun forMonth(billingMonth: YearMonth, palletWiseFrom: YearMonth): CountingRules =
            if (billingMonth.isBefore(palletWiseFrom)) LEGACY else PALLET_WISE
    }
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
