package com.wmspro.tenant.billing.warehousejob

import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.DiscardedBillingInvoices
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.invoice.deriveDisplayStatus
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.snapshot.SnapshotSourceType
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobCancellationService
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobDiscardService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional

/**
 * Infinity's September invoices for NAFIS SIKDER and DENASTER were generated, never sent, and then
 * the customers' minimums changed. Discarding an unsent invoice frees its month to be generated
 * again with the current settings - but never one that was sent, or one that bills GRNs or GINs.
 */
class WarehouseJobDiscardServiceTest {
    private val invoices = Mockito.mock(WmsBillingInvoiceRepository::class.java)
    private val snapshots = Mockito.mock(BillingRunCostSnapshotRepository::class.java)
    private val cancellation = Mockito.mock(WarehouseJobCancellationService::class.java)
    private val discarded = Mockito.mock(DiscardedBillingInvoices::class.java)
    private val service = WarehouseJobDiscardService(invoices, snapshots, cancellation, discarded)

    @Test
    fun `an unsent storage-only invoice is cancelled in FreighAI, kept on record and removed`() {
        val draft = invoice(BillingInvoiceStatus.DRAFT)
        val cancelled = draft.copy(status = BillingInvoiceStatus.CANCELLED)
        Mockito.`when`(invoices.findById(draft.billingInvoiceId)).thenReturn(Optional.of(draft))
        val storage = snapshot(SnapshotSourceType.STORAGE)
        Mockito.`when`(snapshots.findByBillingInvoiceId(draft.billingInvoiceId)).thenReturn(listOf(storage))
        Mockito.`when`(cancellation.cancel(draft.billingInvoiceId, "Minimum changed", "ops", "jwt")).thenReturn(cancelled)

        service.discard(draft.billingInvoiceId, "Minimum changed", "ops", "jwt")

        Mockito.verify(discarded).archive(cancelled)
        Mockito.verify(invoices).deleteById(draft.billingInvoiceId)
    }

    @Test
    fun `a sent invoice cannot be discarded`() {
        val sent = invoice(BillingInvoiceStatus.SUBMITTED)
        Mockito.`when`(invoices.findById(sent.billingInvoiceId)).thenReturn(Optional.of(sent))

        assertThrows(IllegalStateException::class.java) { service.discard(sent.billingInvoiceId, "x", "ops", "jwt") }
        Mockito.verifyNoInteractions(cancellation, discarded)
    }

    @Test
    fun `an invoice billing GRNs or GINs must be cancelled, not discarded`() {
        val draft = invoice(BillingInvoiceStatus.DRAFT)
        Mockito.`when`(invoices.findById(draft.billingInvoiceId)).thenReturn(Optional.of(draft))
        val billed = listOf(snapshot(SnapshotSourceType.STORAGE), snapshot(SnapshotSourceType.INBOUND))
        Mockito.`when`(snapshots.findByBillingInvoiceId(draft.billingInvoiceId)).thenReturn(billed)

        val refusal = assertThrows(IllegalStateException::class.java) { service.discard(draft.billingInvoiceId, "x", "ops", "jwt") }
        assertTrue(refusal.message!!.contains("cancel it instead"))
        Mockito.verifyNoInteractions(cancellation, discarded)
    }

    @Test
    fun `if FreighAI will not cancel, nothing is removed`() {
        val draft = invoice(BillingInvoiceStatus.DRAFT)
        Mockito.`when`(invoices.findById(draft.billingInvoiceId)).thenReturn(Optional.of(draft))
        Mockito.`when`(snapshots.findByBillingInvoiceId(draft.billingInvoiceId)).thenReturn(emptyList())
        Mockito.`when`(cancellation.cancel(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(IllegalStateException("Sales Invoice cannot be cancelled: already sent"))

        assertThrows(IllegalStateException::class.java) { service.discard(draft.billingInvoiceId, "x", "ops", "jwt") }
        Mockito.verifyNoInteractions(discarded)
        Mockito.verify(invoices, Mockito.never()).deleteById(anyString())
    }

    @Test
    fun `a stopped hand-off reads as failed to send, not as still generating`() {
        assertEquals("FAILED_TO_SEND", deriveDisplayStatus(BillingInvoiceStatus.DRAFT, null, WarehouseJobSyncState.MANUAL_REVIEW))
        assertEquals("GENERATING", deriveDisplayStatus(BillingInvoiceStatus.DRAFT, null, WarehouseJobSyncState.PENDING))
        assertEquals("GENERATING", deriveDisplayStatus(BillingInvoiceStatus.DRAFT, null))
    }

    private fun invoice(status: BillingInvoiceStatus) = WmsBillingInvoice(
        billingInvoiceId = "wmsinv_1c08205c35d6c6aa", customerId = 1000409, billingMonth = "2026-09",
        status = status, storageLines = emptyList(), movementLines = emptyList(), serviceLines = emptyList(),
        subtotal = BigDecimal("540.00"), grandTotal = BigDecimal("567.00"),
        freighaiReferenceNo = "WMS-1000409-default-2026-09", generatedAt = Instant.parse("2026-10-01T09:41:06Z"),
        generationContractVersion = "WAREHOUSE_JOB_V1", warehouseJobSyncState = WarehouseJobSyncState.MANUAL_REVIEW
    )

    private fun snapshot(type: SnapshotSourceType): BillingRunCostSnapshot = Mockito.mock(BillingRunCostSnapshot::class.java).also {
        Mockito.`when`(it.sourceType).thenReturn(type)
    }
}
