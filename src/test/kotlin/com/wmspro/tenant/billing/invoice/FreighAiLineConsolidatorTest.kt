package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for [consolidateFreighAiLines]. Pure function — no Spring
 * context; no fixtures beyond the [FreighAiInvoiceLineItem] DTO.
 */
class FreighAiLineConsolidatorTest {

    private fun line(
        desc: String,
        qty: String,
        unit: String,
        price: String,
        ctId: String
    ) = FreighAiInvoiceLineItem(
        description = desc,
        quantity = BigDecimal(qty),
        unit = unit,
        unitPrice = BigDecimal(price),
        chargeTypeId = ctId
    )

    private fun tag(item: FreighAiInvoiceLineItem, source: FreighAiLineSource, idx: Int) =
        TaggedFreighAiLine(item, source, idx)

    /** BigDecimal equality is scale-sensitive; compareTo ignores scale. */
    private fun assertBigDecimalEquals(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual),
            "expected=$expected actual=$actual")
    }

    @Test
    fun `groups with no service lines emit every item as-is`() {
        val storageA = line("Storage – ProjectA – April 2026", "100", "CBM-day", "0.50", "CHG-00114")
        val storageB = line("Storage – ProjectB – April 2026", "50", "CBM-day", "0.50", "CHG-00114")
        val outbound = line("Outbound movement – ProjectA – April 2026", "5", "CBM", "10.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(storageA, FreighAiLineSource.STORAGE, 0),
            tag(storageB, FreighAiLineSource.STORAGE, 1),
            tag(outbound, FreighAiLineSource.MOVEMENT, 2)
        ))

        assertEquals(3, result.size)
        assertEquals(storageA, result[0])
        assertEquals(storageB, result[1])
        assertEquals(outbound, result[2])
    }

    @Test
    fun `outbound plus matching forklift service blends rate into anchor only`() {
        val outbound = line("Outbound movement – ProjectA – April 2026", "5", "CBM", "10.00", "CHG-00067")
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(outbound, FreighAiLineSource.MOVEMENT, 0),
            tag(forklift, FreighAiLineSource.SERVICE, 1)
        ))

        assertEquals(1, result.size)
        val merged = result[0]
        assertEquals("Outbound movement – ProjectA – April 2026", merged.description)
        assertEquals("CBM", merged.unit)
        assertBigDecimalEquals("5", merged.quantity)
        // (5*10 + 1*100) / 5 = 30.00
        assertBigDecimalEquals("30.00", merged.unitPrice)
        assertEquals("CHG-00067", merged.chargeTypeId)
    }

    @Test
    fun `storage with minimum top-up no service emits both lines as-is`() {
        val storage = line("Storage – April 2026", "100", "CBM-day", "0.50", "CHG-00114")
        val topup = line("Storage minimum top-up – April 2026", "1", "topup", "25.00", "CHG-00114")

        val result = consolidateFreighAiLines(listOf(
            tag(storage, FreighAiLineSource.STORAGE, 0),
            tag(topup, FreighAiLineSource.STORAGE, 1)
        ))

        assertEquals(2, result.size)
        assertEquals(storage, result[0])
        assertEquals(topup, result[1])
    }

    @Test
    fun `multi-project outbound with one service preserves smaller project line and folds service into anchor`() {
        // ProjectA: 5 CBM × 10 = 50 (lower, preserved)
        // ProjectB: 8 CBM × 10 = 80 (higher → anchor, absorbs forklift)
        val projA = line("Outbound movement – ProjectA – April 2026", "5", "CBM", "10.00", "CHG-00067")
        val projB = line("Outbound movement – ProjectB – April 2026", "8", "CBM", "10.00", "CHG-00067")
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(projA, FreighAiLineSource.MOVEMENT, 0),
            tag(projB, FreighAiLineSource.MOVEMENT, 1),
            tag(forklift, FreighAiLineSource.SERVICE, 2)
        ))

        assertEquals(2, result.size)
        // ProjectA stays exactly as-is (per-project bifurcation preserved).
        assertEquals(projA, result[0])
        // ProjectB anchor absorbs forklift: (8*10 + 1*100) / 8 = 22.50
        val mergedB = result[1]
        assertEquals("Outbound movement – ProjectB – April 2026", mergedB.description)
        assertEquals("CBM", mergedB.unit)
        assertBigDecimalEquals("8", mergedB.quantity)
        assertBigDecimalEquals("22.50", mergedB.unitPrice)
        assertEquals("CHG-00067", mergedB.chargeTypeId)
    }

    @Test
    fun `service with no matching movement is emitted as-is`() {
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(forklift, FreighAiLineSource.SERVICE, 0)
        ))

        assertEquals(1, result.size)
        assertEquals(forklift, result[0])
    }

    @Test
    fun `inbound and outbound with service routed to outbound merges only outbound`() {
        val inbound = line("Inbound movement – ProjectA – April 2026", "3", "CBM", "5.00", "CHG-00066")
        val outbound = line("Outbound movement – ProjectA – April 2026", "5", "CBM", "10.00", "CHG-00067")
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(inbound, FreighAiLineSource.MOVEMENT, 0),
            tag(outbound, FreighAiLineSource.MOVEMENT, 1),
            tag(forklift, FreighAiLineSource.SERVICE, 2)
        ))

        assertEquals(2, result.size)
        // Inbound untouched, retains original position.
        assertEquals(inbound, result[0])
        // Outbound + forklift merged: (5*10 + 1*100) / 5 = 30.00
        val merged = result[1]
        assertEquals("Outbound movement – ProjectA – April 2026", merged.description)
        assertBigDecimalEquals("5", merged.quantity)
        assertBigDecimalEquals("30.00", merged.unitPrice)
        assertEquals("CHG-00067", merged.chargeTypeId)
    }

    @Test
    fun `multiple service entries all fold into the same anchor`() {
        val outbound = line("Outbound movement – ProjectA – April 2026", "5", "CBM", "10.00", "CHG-00067")
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")
        val labour = line("Labour Out – April 2026", "2", "hour", "25.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(outbound, FreighAiLineSource.MOVEMENT, 0),
            tag(forklift, FreighAiLineSource.SERVICE, 1),
            tag(labour, FreighAiLineSource.SERVICE, 2)
        ))

        assertEquals(1, result.size)
        val merged = result[0]
        // (5*10 + 1*100 + 2*25) / 5 = 200 / 5 = 40.00
        assertBigDecimalEquals("5", merged.quantity)
        assertBigDecimalEquals("40.00", merged.unitPrice)
    }

    @Test
    fun `anchor with zero quantity falls back to emitting all lines as-is`() {
        val zeroOutbound = line("Outbound movement – ProjectA – April 2026", "0", "CBM", "10.00", "CHG-00067")
        val forklift = line("Forklift Out – April 2026", "1", "hour", "100.00", "CHG-00067")

        val result = consolidateFreighAiLines(listOf(
            tag(zeroOutbound, FreighAiLineSource.MOVEMENT, 0),
            tag(forklift, FreighAiLineSource.SERVICE, 1)
        ))

        assertEquals(2, result.size)
        assertEquals(zeroOutbound, result[0])
        assertEquals(forklift, result[1])
    }

    @Test
    fun `empty input returns empty list`() {
        assertEquals(emptyList<FreighAiInvoiceLineItem>(), consolidateFreighAiLines(emptyList()))
    }

    @Test
    fun `service routed to storage chargeType folds into highest-amount storage line`() {
        // Edge case — admin set up a service catalog entry routed to the storage
        // chargeType (e.g. SPECIAL_HANDLING_FEE → CHG-00114). Behavior: same as
        // movement-routed services, just folds into the dominant storage anchor.
        val storA = line("Storage – ProjectA – April 2026", "100", "CBM-day", "0.50", "CHG-00114")
        val storB = line("Storage – ProjectB – April 2026", "200", "CBM-day", "0.50", "CHG-00114")
        val svc = line("Special Fee – April 2026", "1", "lump", "30.00", "CHG-00114")

        val result = consolidateFreighAiLines(listOf(
            tag(storA, FreighAiLineSource.STORAGE, 0),
            tag(storB, FreighAiLineSource.STORAGE, 1),
            tag(svc, FreighAiLineSource.SERVICE, 2)
        ))

        assertEquals(2, result.size)
        // ProjectA preserved.
        assertEquals(storA, result[0])
        // ProjectB anchor absorbs the service: (200*0.50 + 1*30) / 200 = 0.65
        val mergedB = result[1]
        assertEquals("Storage – ProjectB – April 2026", mergedB.description)
        assertEquals("CBM-day", mergedB.unit)
        assertBigDecimalEquals("200", mergedB.quantity)
        assertBigDecimalEquals("0.65", mergedB.unitPrice)
    }
}
