package com.wmspro.tenant.billing.warehousejob

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.adjustment.MovementCostAdjustmentService
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.StorageLine
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.snapshot.SnapshotRef
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentService
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobFreezeService
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobFreezeCompensationException
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobOutboxRepository
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant

class WarehouseJobStandaloneFreezeTest {
    private val invoices = Mockito.mock(WmsBillingInvoiceRepository::class.java)
    private val snapshots = Mockito.mock(BillingRunCostSnapshotRepository::class.java)
    private val outbox = Mockito.mock(WarehouseJobOutboxRepository::class.java)
    private val adjustments = Mockito.mock(MovementCostAdjustmentService::class.java)
    private val intents = Mockito.mock(WarehouseJobClaimIntentService::class.java)
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val service = WarehouseJobFreezeService(
        invoices, snapshots, outbox, WarehouseJobPayloadBuilder(mapper),
        adjustments, intents, mapper
    )

    @BeforeEach
    fun context() {
        TenantContext.setCurrentTenant("199")
        MongoConnectionStorage.setConnection("mongodb://localhost/wms_pro_tenant_199")
        Mockito.`when`(invoices.existsById("wmsinv-1")).thenReturn(false)
        Mockito.`when`(invoices.findByCustomerIdAndProjectCodeAndBillingMonth(1, null, "2026-08")).thenReturn(null)
        Mockito.`when`(snapshots.existsById("candidate-snapshot")).thenReturn(false)
    }

    @AfterEach
    fun clear() {
        TenantContext.clear()
        MongoConnectionStorage.clear()
    }

    @Test
    fun `standalone freeze compensates only locally inserted records when a later insert fails`() {
        Mockito.`when`(invoices.insert(any(WmsBillingInvoice::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(snapshots.insert(any(BillingRunCostSnapshot::class.java)))
            .thenThrow(IllegalStateException("snapshot write failed"))

        assertThrows(IllegalStateException::class.java) {
            service.freezeNewTuple(
                tenantId = "199",
                candidateInvoice = invoice(),
                candidateSnapshots = listOf(snapshot()),
                movementAdjustmentIds = emptyList(),
                customerName = "Infinity Customer",
                freighAiCustomerId = "CUS-1",
                currencyId = "CUR-AED",
                correlationId = "corr"
            )
        }

        Mockito.verify(invoices).deleteById("wmsinv-1")
        Mockito.verifyNoInteractions(outbox)
        Mockito.verify(intents, Mockito.never()).markFrozen("wmsinv-1")
    }

    @Test
    fun `standalone freeze surfaces incomplete compensation for quarantine`() {
        Mockito.`when`(invoices.insert(any(WmsBillingInvoice::class.java))).thenAnswer { it.arguments[0] }
        Mockito.`when`(snapshots.insert(any(BillingRunCostSnapshot::class.java)))
            .thenThrow(IllegalStateException("snapshot write failed"))
        Mockito.doThrow(IllegalStateException("invoice cleanup failed"))
            .`when`(invoices).deleteById("wmsinv-1")

        val failure = assertThrows(WarehouseJobFreezeCompensationException::class.java) {
            service.freezeNewTuple(
                tenantId = "199",
                candidateInvoice = invoice(),
                candidateSnapshots = listOf(snapshot()),
                movementAdjustmentIds = emptyList(),
                customerName = "Infinity Customer",
                freighAiCustomerId = "CUS-1",
                currencyId = "CUR-AED",
                correlationId = "corr"
            )
        }

        org.junit.jupiter.api.Assertions.assertTrue(
            failure.compensationFailures.any { it.startsWith("invoice:wmsinv-1:") }
        )
        Mockito.verify(intents, Mockito.never()).markFrozen("wmsinv-1")
    }

    private fun invoice() = WmsBillingInvoice(
        billingInvoiceId = "wmsinv-1",
        customerId = 1,
        billingMonth = "2026-08",
        status = BillingInvoiceStatus.DRAFT,
        storageLines = listOf(
            StorageLine(
                projectCode = null,
                projectLabel = null,
                cbmDays = BigDecimal.ONE,
                ratePerDay = BigDecimal.TEN,
                amount = BigDecimal.TEN,
                vatPercent = BigDecimal.ZERO,
                vatAmount = BigDecimal.ZERO,
                description = "Storage",
                freighaiChargeTypeId = "CHG-STORAGE"
            )
        ),
        subtotal = BigDecimal.TEN,
        totalVat = BigDecimal.ZERO,
        grandTotal = BigDecimal.TEN,
        freighaiReferenceNo = "WMS-1-default-2026-08",
        generatedAt = Instant.parse("2026-08-31T00:00:00Z")
    )

    private fun snapshot() = BillingRunCostSnapshot(
        snapshotId = "candidate-snapshot",
        billingInvoiceId = "wmsinv-1",
        customerId = 1,
        billingMonth = "2026-08",
        sourceType = SnapshotSourceType.STORAGE,
        sourceRecord = SnapshotRef(SnapshotSourceType.STORAGE, "wmsinv-1-storage-default", "Storage"),
        quantity = BigDecimal.ONE,
        unit = "CBM-day",
        baseCostRate = BigDecimal.ONE,
        effectiveCostRate = BigDecimal.ONE,
        totalCost = BigDecimal.ONE,
        revenueRate = BigDecimal.TEN,
        revenueAmount = BigDecimal.TEN,
        margin = BigDecimal("9"),
        costTreatment = "INTERNAL_STANDARD",
        freighaiChargeTypeId = "CHG-STORAGE"
    )
}
