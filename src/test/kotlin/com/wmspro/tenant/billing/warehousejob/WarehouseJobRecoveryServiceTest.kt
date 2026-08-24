package com.wmspro.tenant.billing.warehousejob

import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.snapshot.*
import com.wmspro.tenant.billing.warehousejob.orchestration.*
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.data.mongodb.core.MongoTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

class WarehouseJobRecoveryServiceTest {
    @Test
    fun `staff retry does not downgrade an already synchronized invoice when no command is retryable`() {
        val invoices = Mockito.mock(WmsBillingInvoiceRepository::class.java)
        val snapshots = Mockito.mock(BillingRunCostSnapshotRepository::class.java)
        val intentRepo = Mockito.mock(WarehouseJobClaimIntentRepository::class.java)
        val intentService = Mockito.mock(WarehouseJobClaimIntentService::class.java)
        val cascade = Mockito.mock(WmsInternalCascadeClient::class.java)
        val payload = Mockito.mock(WarehouseJobPayloadBuilder::class.java)
        val admin = Mockito.mock(WarehouseJobOutboxAdminService::class.java)
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val invoice = invoice().copy(warehouseJobSyncState = WarehouseJobSyncState.SYNCED)
        val intent = WarehouseJobClaimIntent(
            billingInvoiceId = invoice.billingInvoiceId, payloadVersion = 1, customerId = 1,
            projectBucket = "DEFAULT", billingMonth = "2026-09", sourceFingerprint = "hash",
            state = WarehouseJobClaimIntentState.READY, expiresAt = Instant.now()
        )
        Mockito.`when`(invoices.findById(invoice.billingInvoiceId)).thenReturn(Optional.of(invoice))
        Mockito.`when`(intentRepo.findById(invoice.billingInvoiceId)).thenReturn(Optional.of(intent))
        Mockito.`when`(admin.retry(invoice.billingInvoiceId)).thenReturn(0)

        val service = WarehouseJobRecoveryService(
            invoices, snapshots, intentRepo, intentService, cascade, payload, admin, mongo
        )

        val result = service.retry(invoice.billingInvoiceId, "jwt")

        assertEquals(WarehouseJobSyncState.SYNCED, result.warehouseJobSyncState)
        Mockito.verify(mongo, Mockito.never()).updateFirst(
            Mockito.any(), Mockito.any(), Mockito.eq(WmsBillingInvoice::class.java)
        )
    }

    @Test
    fun `staff retry confirms partially committed claims before unquarantining outbox`() {
        val invoices = Mockito.mock(WmsBillingInvoiceRepository::class.java)
        val snapshots = Mockito.mock(BillingRunCostSnapshotRepository::class.java)
        val intentRepo = Mockito.mock(WarehouseJobClaimIntentRepository::class.java)
        val intentService = Mockito.mock(WarehouseJobClaimIntentService::class.java)
        val cascade = Mockito.mock(WmsInternalCascadeClient::class.java)
        val payload = Mockito.mock(WarehouseJobPayloadBuilder::class.java)
        val admin = Mockito.mock(WarehouseJobOutboxAdminService::class.java)
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val invoice = invoice()
        val intent = WarehouseJobClaimIntent(
            billingInvoiceId = invoice.billingInvoiceId, payloadVersion = 1, customerId = 1,
            projectBucket = "DEFAULT", billingMonth = "2026-09", sourceFingerprint = "hash",
            state = WarehouseJobClaimIntentState.MANUAL_REVIEW, expiresAt = Instant.now()
        )
        Mockito.`when`(invoices.findById(invoice.billingInvoiceId)).thenReturn(Optional.of(invoice))
        Mockito.`when`(snapshots.findByBillingInvoiceId(invoice.billingInvoiceId)).thenReturn(listOf(snapshot()))
        Mockito.`when`(intentRepo.findById(invoice.billingInvoiceId)).thenReturn(Optional.of(intent))
        Mockito.`when`(payload.stableCostLineId(anyObj(), anyObj(), anyObj())).thenReturn("cost-grn-1")
        Mockito.`when`(cascade.transitionClaims(anyObj(), anyObj(), Mockito.eq(true), anyObj())).thenReturn(emptyList())
        Mockito.`when`(admin.retry(invoice.billingInvoiceId)).thenReturn(2)

        val service = WarehouseJobRecoveryService(
            invoices, snapshots, intentRepo, intentService, cascade, payload, admin, mongo
        )
        val result = service.retry(invoice.billingInvoiceId, "jwt")

        assertEquals(invoice.billingInvoiceId, result.billingInvoiceId)
        val order = Mockito.inOrder(cascade, intentService, admin)
        order.verify(cascade).transitionClaims(anyObj(), anyObj(), Mockito.eq(true), anyObj())
        order.verify(intentService).markReady(invoice.billingInvoiceId)
        order.verify(admin).retry(invoice.billingInvoiceId)
    }

    private fun invoice() = WmsBillingInvoice(
        billingInvoiceId = "wmsinv-v1", customerId = 1, billingMonth = "2026-09",
        status = BillingInvoiceStatus.DRAFT, freighaiReferenceNo = "WMS-1-default-2026-09",
        generationContractVersion = "WAREHOUSE_JOB_V1", warehouseJobPayloadVersion = 1,
        warehouseJobPayloadHash = "hash"
    )

    private fun snapshot() = BillingRunCostSnapshot(
        snapshotId = "snap-1", billingInvoiceId = "wmsinv-v1", customerId = 1, billingMonth = "2026-09",
        sourceType = SnapshotSourceType.INBOUND, sourceRecord = SnapshotRef(SnapshotSourceType.INBOUND, "grn-1"),
        quantity = BigDecimal.ONE, unit = "CBM", baseCostRate = BigDecimal.ONE,
        effectiveCostRate = BigDecimal.ONE, totalCost = BigDecimal.ONE,
        revenueRate = BigDecimal.ONE, revenueAmount = BigDecimal.ONE, margin = BigDecimal.ZERO,
        generationContractVersion = "WAREHOUSE_JOB_V1", costTreatment = "PARTNER_INVOICE",
        freighaiChargeTypeId = "CHG-IN"
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T { Mockito.any<T>(); return null as T }
}
