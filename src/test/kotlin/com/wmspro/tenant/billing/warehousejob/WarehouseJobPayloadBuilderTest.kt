package com.wmspro.tenant.billing.warehousejob

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.StorageLine
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.SnapshotRef
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class WarehouseJobPayloadBuilderTest {
    private val builder = WarehouseJobPayloadBuilder(ObjectMapper().registerModule(JavaTimeModule()))

    @Test
    fun `canonical payload is stable and contains explicit cost treatment`() {
        val invoice = invoice("WAREHOUSE_JOB_V1")
        val snapshot = snapshot()
        val first = builder.build("tenant-1", invoice, listOf(snapshot), "Customer", "AED", 1)
        val second = builder.build("tenant-1", invoice, listOf(snapshot), "Customer", "AED", 1)
        assertEquals(first.sha256, second.sha256)
        assertEquals(first.canonicalJson, second.canonicalJson)
        assert(first.canonicalJson.contains("INTERNAL_STANDARD"))
        assertEquals("charge-1", first.request.commercialSnapshot.plannedCostLines.single().chargeTypeId)
        assertEquals("charge-1", first.request.commercialSnapshot.sellingLines.single().serviceCode)
    }

    @Test
    fun `unmarked legacy invoice is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build("tenant-1", invoice(null), listOf(snapshot()), null, "AED", 1)
        }
    }

    private fun invoice(marker: String?) = WmsBillingInvoice(
        billingInvoiceId = "wmsinv-1",
        customerId = 42,
        billingMonth = "2026-08",
        status = BillingInvoiceStatus.DRAFT,
        storageLines = listOf(
            StorageLine(null, null, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "Storage", "charge-1")
        ),
        subtotal = BigDecimal.TEN,
        grandTotal = BigDecimal.TEN,
        freighaiReferenceNo = "WMS-42-default-2026-08",
        generatedAt = Instant.parse("2026-08-31T00:00:00Z"),
        generationContractVersion = marker,
        warehouseJobExternalReference = "WMS-42-default-2026-08",
        warehouseJobPayloadVersion = if (marker == null) null else 1
    )

    private fun snapshot() = BillingRunCostSnapshot(
        snapshotId = "snap-1",
        billingInvoiceId = "wmsinv-1",
        customerId = 42,
        billingMonth = "2026-08",
        sourceType = SnapshotSourceType.STORAGE,
        sourceRecord = SnapshotRef(SnapshotSourceType.STORAGE, "item-1"),
        quantity = BigDecimal.TEN,
        unit = "CBM-day",
        baseCostRate = BigDecimal.ONE,
        effectiveCostRate = BigDecimal.ONE,
        totalCost = BigDecimal.TEN,
        revenueRate = BigDecimal.ONE,
        revenueAmount = BigDecimal.TEN,
        margin = BigDecimal.ZERO,
        generationContractVersion = "WAREHOUSE_JOB_V1",
        costLineId = "cost-1",
        costTreatment = "INTERNAL_STANDARD",
        freighaiChargeTypeId = "charge-1",
        completionWeight = BigDecimal.TEN,
        calculationVersion = "WMS_COST_V1"
    )
}
