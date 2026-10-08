package com.wmspro.tenant.billing.invoice.aggregator

import org.bson.Document
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.util.Date

/**
 * Storage from October 2026 is counted pallet-wise: top-level stock only, undated migrated stock
 * from its first-received date, receipts from the GRN's actual receiving date, shipped stock until
 * the day it left, and no empty pallets minted for re-palletisation. Every case below was found in
 * live Infinity data.
 */
class OccupancyCountingRulesTest {
    private val october = YearMonth.of(2026, 10)
    private val oneCubicMetre = Document(mapOf("lengthCm" to 100.0, "widthCm" to 100.0, "heightCm" to 100.0))

    @Test
    fun `pallet-wise rules count each piece of stock for the days it was actually there`() {
        val mongo = Mockito.mock(MongoTemplate::class.java)
        // Migrated pallet: no createdAt, only a first-received date. Holds an item, so it is stock.
        val migratedPallet = item(1, "PALLET", firstReceived = "2026-08-01", children = listOf(99L))
        // Pallet minted empty for re-palletisation: no receipt behind it, nothing on it.
        val emptyPallet = item(2, "PALLET", createdAt = "2026-09-20")
        // Shipped on 11 Oct: still status AVAILABLE, only its location says it left.
        val shipped = item(3, "SKU_ITEM", createdAt = "2026-09-01", location = "IN_TRANSIT")
        // Booked on 20 Oct for goods that arrived on 5 Oct.
        val bookedLate = item(4, "SKU_ITEM", createdAt = "2026-10-20")
        stub(mongo, "storage_items", migratedPallet, emptyPallet, shipped, bookedLate)
        stub(mongo, "receiving_records", Document(mapOf(
            "_id" to "RCV-1", "receivedDate" to date("2026-10-05"), "warehouseId" to "WH1",
            "receivedItems" to listOf(Document("createdStorageItems", listOf(4L)))
        )))
        stub(mongo, "item_transactions", Document(mapOf(
            "storageItemId" to 3L, "transactionType" to "LOCATION_CHANGE", "timestamp" to date("2026-10-11"),
            "beforeLocation" to Document("locationCode", "WH1-B-2-1-B"),
            "afterLocation" to Document("locationCode", "IN_TRANSIT")
        )))
        stub(mongo, "quantity_based_inventory")

        val result = OccupancyAggregator(mongo, WarehouseAttribution(mongo))
            .aggregate(6995L, october, CountingRules.PALLET_WISE)
            .getValue(null)

        // Pallet all 31 days + shipped item 1-10 Oct + late-booked item 5-31 Oct; empty pallet 0.
        assertEquals(0, BigDecimal(31 + 10 + 27).compareTo(result.cbmDaysByProject.getValue(null)))
        assertEquals(setOf("1", "3", "4"), result.contributionsByProject.getValue(null).map { it.sourceId }.toSet())
    }

    @Test
    fun `split by warehouse, a pallet moved mid-month is billed to each warehouse for its own days`() {
        val mongo = Mockito.mock(MongoTemplate::class.java)
        stub(mongo, "warehouses",
            Document(mapOf("_id" to "WH1", "name" to "Infinity DIP Warehouse")),
            Document(mapOf("_id" to "WH2", "name" to "Infinity Ajman Warehouse")))
        // Received into Ajman before October, moved to DIP on 11 Oct, still there.
        stub(mongo, "storage_items", item(1, "PALLET", createdAt = "2026-09-01", location = "WH1-A-1-0-A", children = listOf(99L)))
        stub(mongo, "receiving_records")
        stub(mongo, "item_transactions", Document(mapOf(
            "storageItemId" to 1L, "transactionType" to "LOCATION_CHANGE", "timestamp" to date("2026-10-11"),
            "beforeLocation" to Document("locationCode", "WH2-D-1-1-A"),
            "afterLocation" to Document("locationCode", "WH1-A-1-0-A")
        )))
        stub(mongo, "quantity_based_inventory")

        val result = OccupancyAggregator(mongo, WarehouseAttribution(mongo))
            .aggregate(6995L, october, CountingRules.PALLET_WISE, splitByWarehouse = true)

        assertEquals(setOf("WH1", "WH2"), result.keys)
        assertEquals(0, BigDecimal(21).compareTo(result.getValue("WH1").cbmDaysByProject.getValue(null)))
        assertEquals(0, BigDecimal(10).compareTo(result.getValue("WH2").cbmDaysByProject.getValue(null)))
    }

    @Test
    fun `months before the cut-over keep the legacy rules they were invoiced under`() {
        assertEquals(CountingRules.LEGACY, CountingRules.forMonth(YearMonth.of(2026, 9), october))
        assertEquals(CountingRules.PALLET_WISE, CountingRules.forMonth(october, october))
        assertEquals(CountingRules.PALLET_WISE, CountingRules.forMonth(YearMonth.of(2027, 1), october))
    }

    private fun stub(mongo: MongoTemplate, collection: String, vararg docs: Document) {
        Mockito.`when`(mongo.find(Mockito.any(Query::class.java), Mockito.eq(Document::class.java), Mockito.eq(collection)))
            .thenReturn(docs.toList())
    }

    private fun item(
        id: Long,
        type: String,
        createdAt: String? = null,
        firstReceived: String? = null,
        location: String = "WH1-B-2-1-B",
        children: List<Long> = emptyList()
    ) = Document(buildMap<String, Any> {
        put("_id", id)
        put("accountId", 6995L)
        put("itemType", type)
        put("currentStatus", "AVAILABLE")
        put("currentLocation", Document("locationCode", location))
        put("dimensions", oneCubicMetre)
        put("childItems", children)
        createdAt?.let { put("createdAt", date(it)) }
        firstReceived?.let { put("agingInfo", Document("firstReceivedDate", date(it))) }
    })

    private fun date(day: String) = Date.from(Instant.parse("${day}T00:00:00Z"))
}
