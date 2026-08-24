package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.CancelResult
import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.tenant.billing.adjustment.MovementCostAdjustmentService
import com.wmspro.tenant.billing.catalog.ServiceCatalogRepository
import com.wmspro.tenant.billing.costs.TenantOperationalCostsService
import com.wmspro.tenant.billing.defaults.TenantBillingDefaultsService
import com.wmspro.tenant.billing.invoice.aggregator.MovementAggregator
import com.wmspro.tenant.billing.invoice.aggregator.OccupancyAggregator
import com.wmspro.tenant.billing.invoice.aggregator.ServiceLogAggregator
import com.wmspro.tenant.billing.invoice.cascade.CascadeOutcome
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.repository.AccountIdMappingRepository
import com.wmspro.tenant.service.AccountIdMappingService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.Optional

/**
 * Cancel-path tests for [BillingRunService].
 *
 * These exist for one specific hazard. FreighAi refuses to cancel an invoice
 * that has already been issued (it wants a credit note instead). WMS used to
 * log that refusal and carry on: the invoice was marked CANCELLED locally and
 * its GRNs/GINs/ServiceLogs were unlocked, so the next billing run re-billed
 * work the customer had already been invoiced for — while the original invoice
 * was still live in FreighAi. Cancelling must now be all-or-nothing.
 */
class BillingRunCancelTest {

    private lateinit var invoiceRepository: WmsBillingInvoiceRepository
    private lateinit var freighAiInvoiceClient: FreighAiInvoiceClient
    private lateinit var cascadeClient: WmsInternalCascadeClient
    private lateinit var costSnapshotRepository: BillingRunCostSnapshotRepository
    private lateinit var movementCostAdjustmentService: MovementCostAdjustmentService
    private lateinit var service: BillingRunService

    private val invoiceId = "wmsinv_cancel001"
    private val freighaiId = "INV-9"
    private val token = "Bearer t"

    @BeforeEach
    fun setUp() {
        invoiceRepository = Mockito.mock(WmsBillingInvoiceRepository::class.java)
        freighAiInvoiceClient = Mockito.mock(FreighAiInvoiceClient::class.java)
        cascadeClient = Mockito.mock(WmsInternalCascadeClient::class.java)
        costSnapshotRepository = Mockito.mock(BillingRunCostSnapshotRepository::class.java)
        movementCostAdjustmentService = Mockito.mock(MovementCostAdjustmentService::class.java)

        service = BillingRunService(
            invoiceRepository = invoiceRepository,
            billingProfileRepository = Mockito.mock(CustomerBillingProfileRepository::class.java),
            catalogRepository = Mockito.mock(ServiceCatalogRepository::class.java),
            accountIdMappingService = Mockito.mock(AccountIdMappingService::class.java),
            accountIdMappingRepository = Mockito.mock(AccountIdMappingRepository::class.java),
            occupancyAggregator = Mockito.mock(OccupancyAggregator::class.java),
            movementAggregator = Mockito.mock(MovementAggregator::class.java),
            serviceLogAggregator = Mockito.mock(ServiceLogAggregator::class.java),
            freighAiInvoiceClient = freighAiInvoiceClient,
            freighAiChargeTypeClient = Mockito.mock(FreighAiChargeTypeClient::class.java),
            cascadeClient = cascadeClient,
            tenantBillingDefaultsService = Mockito.mock(TenantBillingDefaultsService::class.java),
            tenantOperationalCostsService = Mockito.mock(TenantOperationalCostsService::class.java),
            costSnapshotRepository = costSnapshotRepository,
            movementCostAdjustmentService = movementCostAdjustmentService,
            warehouseJobGenerationService = Mockito.mock(com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobGenerationService::class.java),
            customerNameResolver = Mockito.mock(CustomerNameResolver::class.java)
        )
    }

    private fun invoice(
        status: BillingInvoiceStatus = BillingInvoiceStatus.SUBMITTED,
        freighaiInvoiceId: String? = freighaiId
    ) = WmsBillingInvoice(
        billingInvoiceId = invoiceId,
        customerId = 7L,
        billingMonth = "2026-04",
        status = status,
        freighaiInvoiceId = freighaiInvoiceId,
        freighaiInvoiceNo = "SI-0099",
        freighaiReferenceNo = "WMS-7-default-2026-04",
        grandTotal = BigDecimal("500.00"),
        movementLines = listOf(
            MovementLine(
                direction = MovementDirection.INBOUND,
                projectCode = null,
                projectLabel = null,
                totalCbm = BigDecimal("10"),
                ratePerCbm = BigDecimal("50"),
                amount = BigDecimal("500.00"),
                vatPercent = BigDecimal("5"),
                vatAmount = BigDecimal("25.00"),
                description = "Handling in",
                freighaiChargeTypeId = "CHG-1",
                sourceRecordIds = listOf("RR-1", "RR-2")
            )
        )
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T {
        Mockito.any<T>()
        return null as T
    }

    private fun <T : Any> eqv(value: T): T {
        Mockito.eq(value)
        return value
    }

    @Test
    fun `refuses to cancel locally when FreighAi rejects an issued invoice`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice()))
        Mockito.`when`(freighAiInvoiceClient.cancelInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(CancelResult.Rejected("Cannot cancel a SENT invoice. Create a Credit Note instead."))

        assertThatThrownBy { service.cancel(invoiceId, "customer asked", "admin@x.com", token) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Credit Note")

        // The whole point: nothing local may move, or those GRNs get re-billed
        // while the customer still holds a live invoice.
        Mockito.verify(cascadeClient, Mockito.never()).clearLocks(anyObj(), anyObj(), anyObj(), anyObj())
        Mockito.verify(invoiceRepository, Mockito.never()).save(anyObj<WmsBillingInvoice>())
        Mockito.verify(costSnapshotRepository, Mockito.never()).deleteByBillingInvoiceId(anyObj())
    }

    @Test
    fun `refuses to cancel locally when FreighAi is unreachable`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice()))
        Mockito.`when`(freighAiInvoiceClient.cancelInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(CancelResult.Unreachable("connection reset"))

        assertThatThrownBy { service.cancel(invoiceId, "customer asked", "admin@x.com", token) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Could not reach FreighAi")

        Mockito.verify(cascadeClient, Mockito.never()).clearLocks(anyObj(), anyObj(), anyObj(), anyObj())
        Mockito.verify(invoiceRepository, Mockito.never()).save(anyObj<WmsBillingInvoice>())
    }

    @Test
    fun `cancels locally once FreighAi accepts`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice()))
        Mockito.`when`(freighAiInvoiceClient.cancelInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(CancelResult.Success)
        Mockito.`when`(cascadeClient.clearLocks(anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(CascadeOutcome(emptyList(), emptyList(), emptyList()))
        Mockito.`when`(invoiceRepository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.cancel(invoiceId, "duplicate run", "admin@x.com", token)

        assertThat(result.status).isEqualTo(BillingInvoiceStatus.CANCELLED)
        assertThat(result.cancelReason).isEqualTo("duplicate run")
        Mockito.verify(cascadeClient).clearLocks(anyObj(), anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `treats an already-cancelled FreighAi invoice as cleanup to finish`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice()))
        Mockito.`when`(freighAiInvoiceClient.cancelInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(CancelResult.Rejected("Invoice is already cancelled"))
        Mockito.`when`(cascadeClient.clearLocks(anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(CascadeOutcome(emptyList(), emptyList(), emptyList()))
        Mockito.`when`(invoiceRepository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.cancel(invoiceId, "cleanup", "admin@x.com", token)

        assertThat(result.status).isEqualTo(BillingInvoiceStatus.CANCELLED)
        Mockito.verify(cascadeClient).clearLocks(anyObj(), anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `cancels an unbound invoice without calling FreighAi`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId))
            .thenReturn(Optional.of(invoice(freighaiInvoiceId = null)))
        Mockito.`when`(cascadeClient.clearLocks(anyObj(), anyObj(), anyObj(), anyObj()))
            .thenReturn(CascadeOutcome(emptyList(), emptyList(), emptyList()))
        Mockito.`when`(invoiceRepository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.cancel(invoiceId, "never submitted", "admin@x.com", token)

        assertThat(result.status).isEqualTo(BillingInvoiceStatus.CANCELLED)
        Mockito.verify(freighAiInvoiceClient, Mockito.never()).cancelInvoice(anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `returns immediately for an invoice already cancelled in WMS`() {
        Mockito.`when`(invoiceRepository.findById(invoiceId))
            .thenReturn(Optional.of(invoice(status = BillingInvoiceStatus.CANCELLED)))

        val result = service.cancel(invoiceId, "again", "admin@x.com", token)

        assertThat(result.status).isEqualTo(BillingInvoiceStatus.CANCELLED)
        Mockito.verify(freighAiInvoiceClient, Mockito.never()).cancelInvoice(anyObj(), anyObj(), anyObj())
        Mockito.verify(invoiceRepository, Mockito.never()).save(anyObj<WmsBillingInvoice>())
    }
}
