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
 * storage_items ids are numeric (Long). The aggregator read them with `getString("_id")`, which
 * threw ClassCastException on the first storage item it met - failing the billing preview and run
 * for every customer with a dated storage item. Endurance (account 6791) could not be invoiced for
 * September 2026 because of it.
 */
class OccupancyAggregatorTest {

    @Test
    fun `numeric storage item ids are billed and reported instead of failing the run`() {
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val box = storageItem(82388L, Document(mapOf("lengthCm" to 100.0, "widthCm" to 100.0, "heightCm" to 100.0)))
        val noDimensions = storageItem(82389L, dimensions = null)
        Mockito.`when`(mongo.find(Mockito.any(Query::class.java), Mockito.eq(Document::class.java), Mockito.eq("storage_items")))
            .thenReturn(listOf(box, noDimensions))
        Mockito.`when`(mongo.find(Mockito.any(Query::class.java), Mockito.eq(Document::class.java), Mockito.eq("quantity_based_inventory")))
            .thenReturn(emptyList())

        val result = OccupancyAggregator(mongo).aggregate(6791L, YearMonth.of(2026, 9))

        // 1 m3 for all 30 days of September
        assertEquals(0, BigDecimal("30").compareTo(result.cbmDaysByProject.getValue(null)))
        assertEquals(listOf("82388"), result.contributionsByProject.getValue(null).map { it.sourceId })
        assertEquals(listOf("82389"), result.warnings.map { it.affectedId })
    }

    private fun storageItem(id: Long, dimensions: Document?) = Document(
        buildMap<String, Any> {
            put("_id", id)
            put("accountId", 6791L)
            put("createdAt", Date.from(Instant.parse("2025-11-18T13:03:58Z")))
            put("currentStatus", "AVAILABLE")
            dimensions?.let { put("dimensions", it) }
        }
    )
}
