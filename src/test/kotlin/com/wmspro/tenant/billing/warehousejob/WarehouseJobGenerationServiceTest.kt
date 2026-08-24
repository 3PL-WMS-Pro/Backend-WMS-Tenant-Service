package com.wmspro.tenant.billing.warehousejob

import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.cascade.*
import com.wmspro.tenant.billing.snapshot.*
import com.wmspro.tenant.billing.warehousejob.orchestration.*
import com.wmspro.tenant.billing.warehousejob.payload.WarehouseJobPayloadBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import java.math.BigDecimal

class WarehouseJobGenerationServiceTest {
    private val intents = Mockito.mock(WarehouseJobClaimIntentService::class.java)
    private val cascade = Mockito.mock(WmsInternalCascadeClient::class.java)
    private val freeze = Mockito.mock(WarehouseJobFreezeService::class.java)
    private val payload = Mockito.mock(WarehouseJobPayloadBuilder::class.java)
    private val admin = Mockito.mock(WarehouseJobOutboxAdminService::class.java)
    private val worker = Mockito.mock(WarehouseJobOutboxWorker::class.java)
    private val service = WarehouseJobGenerationService(intents, cascade, freeze, payload, admin, worker)

    @BeforeEach fun context() {
        TenantContext.setCurrentTenant("7")
        MongoConnectionStorage.setConnection("mongodb://localhost/tenant7")
        Mockito.`when`(payload.stableCostLineId(anyObj(), anyObj(), anyObj())).thenReturn("cost-grn-1")
    }

    @AfterEach fun clear() { TenantContext.clear(); MongoConnectionStorage.clear() }

    @Test
    fun `partial source commit quarantines commands and never reports ready`() {
        val invoice = invoice()
        val snapshot = inboundSnapshot()
        val claimed = ClaimedBillingSource(BillingSourceKind.GRN, BillingClaimTarget("grn-1", "cost-grn-1"))
        Mockito.`when`(cascade.reserveClaims(anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(ClaimCascadeOutcome(listOf(claimed), emptyList()))
        Mockito.`when`(freeze.freezeNewTuple(anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(FrozenWarehouseJobRun(invoice, listOf(snapshot), emptyList()))
        Mockito.`when`(cascade.transitionClaims(anyObj(), anyObj(), Mockito.eq(true), anyObj()))
            .thenReturn(listOf("GRN:grn-1"))

        assertThrows(IllegalStateException::class.java) {
            service.generateNewTuple(invoice, listOf(snapshot), emptyList(), "Customer", "CUS-1", "CUR-AED", "jwt", "corr")
        }

        Mockito.verify(intents).markManualReview(eqv(invoice.billingInvoiceId), anyObj())
        Mockito.verify(admin).quarantine(eqv(invoice.billingInvoiceId), anyObj())
        Mockito.verify(intents, Mockito.never()).markReady(invoice.billingInvoiceId)
    }

    @Test
    fun `reservation failure releases exact owner and does not freeze`() {
        val invoice = invoice()
        val claimed = ClaimedBillingSource(BillingSourceKind.GRN, BillingClaimTarget("grn-1", "cost-grn-1"))
        Mockito.`when`(cascade.reserveClaims(anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(ClaimCascadeOutcome(listOf(claimed), listOf("GIN:gin-1")))
        Mockito.`when`(cascade.transitionClaims(anyObj(), anyObj(), Mockito.eq(false), anyObj())).thenReturn(emptyList())

        assertThrows(WarehouseJobCutoverConflict::class.java) {
            service.generateNewTuple(invoice, listOf(inboundSnapshot()), emptyList(), "Customer", "CUS-1", "CUR-AED", "jwt", "corr")
        }

        Mockito.verify(freeze, Mockito.never()).freezeNewTuple(anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj())
        Mockito.verify(intents).markReleased(eqv(invoice.billingInvoiceId), anyObj())
    }

    @Test
    fun `incomplete local compensation quarantines without releasing source claims`() {
        val invoice = invoice()
        val snapshot = inboundSnapshot()
        val claimed = ClaimedBillingSource(BillingSourceKind.GRN, BillingClaimTarget("grn-1", "cost-grn-1"))
        Mockito.`when`(cascade.reserveClaims(anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(ClaimCascadeOutcome(listOf(claimed), emptyList()))
        Mockito.`when`(freeze.freezeNewTuple(anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenThrow(
                WarehouseJobFreezeCompensationException(
                    "cleanup incomplete",
                    IllegalStateException("snapshot failed"),
                    listOf("invoice:wmsinv-v1:cleanup failed")
                )
            )

        assertThrows(WarehouseJobFreezeCompensationException::class.java) {
            service.generateNewTuple(
                invoice, listOf(snapshot), emptyList(), "Customer", "CUS-1", "CUR-AED", "jwt", "corr"
            )
        }

        Mockito.verify(intents).markManualReview(eqv(invoice.billingInvoiceId), anyObj())
        Mockito.verify(admin).quarantine(eqv(invoice.billingInvoiceId), anyObj())
        Mockito.verify(cascade, Mockito.never()).transitionClaims(
            anyObj(),
            anyObj(),
            Mockito.anyBoolean(),
            anyObj()
        )
        Mockito.verify(intents, Mockito.never()).markReleased(eqv(invoice.billingInvoiceId), anyObj())
    }

    @Test
    fun `successful manual run immediately drains Warehouse Job and draft SI with caller token`() {
        val invoice = invoice()
        val snapshot = inboundSnapshot()
        val claimed = ClaimedBillingSource(BillingSourceKind.GRN, BillingClaimTarget("grn-1", "cost-grn-1"))
        Mockito.`when`(cascade.reserveClaims(anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(ClaimCascadeOutcome(listOf(claimed), emptyList()))
        Mockito.`when`(freeze.freezeNewTuple(anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(FrozenWarehouseJobRun(invoice, listOf(snapshot), emptyList()))
        Mockito.`when`(cascade.transitionClaims(anyObj(), anyObj(), Mockito.eq(true), anyObj()))
            .thenReturn(emptyList())
        Mockito.`when`(freeze.current(invoice.billingInvoiceId)).thenReturn(invoice)

        val result = service.generateNewTuple(
            invoice, listOf(snapshot), emptyList(), "Customer", "CUS-1", "CUR-AED", "caller-jwt", "corr"
        )

        assertEquals(invoice.billingInvoiceId, result.billingInvoiceId)
        Mockito.verify(intents).markReady(invoice.billingInvoiceId)
        Mockito.verify(worker).drainInvoice("wms-request-corr", invoice.billingInvoiceId, "caller-jwt")
    }

    private fun invoice() = WmsBillingInvoice(
        billingInvoiceId = "wmsinv-v1", customerId = 1, billingMonth = "2026-09",
        status = BillingInvoiceStatus.DRAFT, freighaiReferenceNo = "WMS-1-default-2026-09"
    )

    private fun inboundSnapshot() = BillingRunCostSnapshot(
        snapshotId = "snap-1", billingInvoiceId = "wmsinv-v1", customerId = 1, billingMonth = "2026-09",
        sourceType = SnapshotSourceType.INBOUND, sourceRecord = SnapshotRef(SnapshotSourceType.INBOUND, "grn-1"),
        quantity = BigDecimal.ONE, unit = "CBM", baseCostRate = BigDecimal.ONE,
        effectiveCostRate = BigDecimal.ONE, totalCost = BigDecimal.ONE,
        revenueRate = BigDecimal.TEN, revenueAmount = BigDecimal.TEN, margin = BigDecimal("9"),
        costTreatment = "PARTNER_INVOICE", freighaiChargeTypeId = "CHG-IN"
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T { Mockito.any<T>(); return null as T }
    private fun <T : Any> eqv(value: T): T { Mockito.eq(value); return value }
}
