package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.*
import com.wmspro.common.external.freighai.dto.FreighAiChargeType
import com.wmspro.tenant.billing.adjustment.*
import com.wmspro.tenant.billing.catalog.ServiceCatalogRepository
import com.wmspro.tenant.billing.costs.TenantOperationalCostsService
import com.wmspro.tenant.billing.defaults.TenantBillingDefaultsService
import com.wmspro.tenant.billing.invoice.aggregator.*
import com.wmspro.tenant.billing.invoice.cascade.WmsInternalCascadeClient
import com.wmspro.tenant.billing.profile.*
import com.wmspro.tenant.billing.snapshot.*
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobGenerationService
import com.wmspro.tenant.model.AccountIdMapping
import com.wmspro.tenant.repository.AccountIdMappingRepository
import com.wmspro.tenant.service.AccountIdMappingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.YearMonth
import java.util.Optional

/**
 * Denaster stores goods in both the Ajman and DIP warehouses and is invoiced separately for each.
 * With `invoicePerWarehouse` on, the run emits one invoice per warehouse, each at its own rate and
 * with its own minimum, and says on every line which warehouse it is for.
 */
class PerWarehouseBillingRunTest {
    @Test
    fun `a customer invoiced per warehouse gets one invoice per warehouse with its own rate and minimum`() {
        val month = YearMonth.of(2026, 10)
        val invoices = mock(WmsBillingInvoiceRepository::class.java)
        val profiles = mock(CustomerBillingProfileRepository::class.java)
        val mappings = mock(AccountIdMappingRepository::class.java)
        val occupancy = mock(OccupancyAggregator::class.java)
        val movement = mock(MovementAggregator::class.java)
        val services = mock(ServiceLogAggregator::class.java)
        val charges = mock(FreighAiChargeTypeClient::class.java)
        val attribution = mock(WarehouseAttribution::class.java)
        val generated = mutableListOf<WmsBillingInvoice>()
        val generator = mock(WarehouseJobGenerationService::class.java) { call ->
            if (call.method.name == "generateNewTuple") {
                (call.arguments[0] as WmsBillingInvoice).also { generated += it }
            } else RETURNS_DEFAULTS.answer(call)
        }
        val expenses = mock(SupplierExpenseService::class.java)
        val names = mock(CustomerNameResolver::class.java)

        `when`(profiles.findById(6995)).thenReturn(Optional.of(CustomerBillingProfile(
            6995,
            defaultCbmRatePerDay = BigDecimal("1.40"),
            // Charged once per warehouse if it applied here, so it must not.
            defaultMonthlyMinimum = BigDecimal("2985"),
            invoicePerWarehouse = true,
            warehouseRates = listOf(
                WarehouseRate("WH1", cbmRatePerDay = BigDecimal("1.50"), monthlyMinimum = BigDecimal("775")),
                WarehouseRate("WH2", monthlyMinimum = BigDecimal("2210"))
            ),
            billingEnabled = true,
            freighaiStorageChargeTypeId = "CHG-S", freighaiInboundMovementChargeTypeId = "CHG-S",
            freighaiOutboundMovementChargeTypeId = "CHG-S"
        )))
        `when`(charges.listChargeTypes("token", false)).thenReturn(listOf(FreighAiChargeType("CHG-S", "Storage", BigDecimal("5"))))
        `when`(attribution.warehouseNames()).thenReturn(mapOf("WH1" to "Infinity DIP Warehouse", "WH2" to "Infinity Ajman Warehouse"))
        `when`(occupancy.aggregate(6995, month, CountingRules.PALLET_WISE, true)).thenReturn(mapOf(
            "WH1" to OccupancyResult(mapOf(null to BigDecimal("390.0000")), emptyList()),   // 1.50 × 390 = 585
            "WH2" to OccupancyResult(mapOf(null to BigDecimal("1190.0000")), emptyList())   // 1.40 × 1190 = 1666
        ))
        `when`(movement.aggregateInbound(6995, month, true)).thenReturn(emptyMap())
        `when`(movement.aggregateOutbound(6995, month, true)).thenReturn(emptyMap())
        `when`(services.aggregate(6995, month, true)).thenReturn(emptyMap())
        `when`(expenses.forMonth(6995, month.toString())).thenReturn(emptyList())
        `when`(names.resolve(setOf(6995L), "token")).thenReturn(mapOf(6995L to "DENASTER GENERAL TRADING LLC"))
        `when`(mappings.findById(6995)).thenReturn(Optional.of(AccountIdMapping(6995, "cust_316cdbbfa408", "EDBTXW", "leadtorev_match")))

        val service = BillingRunService(invoices, profiles, mock(ServiceCatalogRepository::class.java),
            mock(AccountIdMappingService::class.java), mappings, occupancy, movement, services,
            mock(FreighAiInvoiceClient::class.java), charges, mock(WmsInternalCascadeClient::class.java),
            mock(TenantBillingDefaultsService::class.java), mock(TenantOperationalCostsService::class.java),
            mock(BillingRunCostSnapshotRepository::class.java), mock(MovementCostAdjustmentService::class.java),
            generator, names, expenses, mock(SupplierExpenseSyncService::class.java),
            attribution, mock(BillingInvoiceIndexMigration::class.java))
        ReflectionTestUtils.setField(service, "aedCurrencyId", "CUR-AED")
        ReflectionTestUtils.setField(service, "palletWiseCountingFrom", "2026-10")

        val result = service.generate(6995, month.toString(), "manager", "token")

        assertEquals(listOf("WH1", "WH2"), result.map { it.warehouseId })
        val dip = generated.single { it.warehouseId == "WH1" }
        val ajman = generated.single { it.warehouseId == "WH2" }
        // Each warehouse is topped up to its own minimum, not the customer's 2,985.
        assertEquals(0, BigDecimal("775").compareTo(dip.subtotal))
        assertEquals(0, BigDecimal("2210").compareTo(ajman.subtotal))
        assertEquals(0, BigDecimal("1.50").compareTo(dip.storageLines.single().ratePerDay))
        assertEquals("Storage – October 2026 – Infinity DIP Warehouse", dip.storageLines.single().description)
        assertEquals("WMS-6995-default-2026-10-WH1", dip.freighaiReferenceNo)
        assertNotEquals(dip.billingInvoiceId, ajman.billingInvoiceId)
    }
}
