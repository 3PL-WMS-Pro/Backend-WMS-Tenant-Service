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
import java.time.LocalDate
import java.time.YearMonth
import java.util.Optional

class SupplierExpenseBillingRunTest {
    @Test
    fun `supplier-only project survives zero customer rates and zero movement volume`() {
        val invoices = mock(WmsBillingInvoiceRepository::class.java)
        val profiles = mock(CustomerBillingProfileRepository::class.java)
        val mappings = mock(AccountIdMappingRepository::class.java)
        val occupancy = mock(OccupancyAggregator::class.java)
        val movement = mock(MovementAggregator::class.java)
        val services = mock(ServiceLogAggregator::class.java)
        val charges = mock(FreighAiChargeTypeClient::class.java)
        var generatedInvoice: WmsBillingInvoice? = null
        var generatedCosts = emptyList<BillingRunCostSnapshot>()
        val generator = mock(WarehouseJobGenerationService::class.java) { call ->
            if (call.method.name == "generateNewTuple") {
                generatedInvoice = call.arguments[0] as WmsBillingInvoice
                @Suppress("UNCHECKED_CAST")
                generatedCosts = call.arguments[1] as List<BillingRunCostSnapshot>
                generatedInvoice
            } else RETURNS_DEFAULTS.answer(call)
        }
        val names = mock(CustomerNameResolver::class.java)
        val expenses = mock(SupplierExpenseService::class.java)
        val month = YearMonth.of(2026, 8)
        val expense = SupplierExpense("expense-123", 42,
            AdjustmentAttachedRef(AdjustmentAttachedType.GRN, "GRN-1", "GRN-1"), "PROJECT_A",
            LocalDate.of(2026, 8, 10), month.toString(), "Forklift", BigDecimal("300.00"),
            chargeTypeId = "CHG-1", createdBy = "manager")
        `when`(profiles.findById(42)).thenReturn(Optional.of(CustomerBillingProfile(42,
            defaultCbmRatePerDay = BigDecimal.ZERO, defaultInboundCbmRate = BigDecimal.ZERO,
            defaultOutboundCbmRate = BigDecimal.ZERO, billingEnabled = true,
            freighaiStorageChargeTypeId = "CHG-1", freighaiInboundMovementChargeTypeId = "CHG-1", freighaiOutboundMovementChargeTypeId = "CHG-1")))
        `when`(charges.listChargeTypes("token", false)).thenReturn(listOf(FreighAiChargeType("CHG-1", "Handling", BigDecimal.ZERO)))
        `when`(occupancy.aggregate(42, month)).thenReturn(OccupancyResult(emptyMap(), emptyList()))
        `when`(movement.aggregateInbound(42, month)).thenReturn(InboundMovementResult(emptyMap(), emptyList()))
        `when`(movement.aggregateOutbound(42, month)).thenReturn(OutboundMovementResult(emptyMap(), emptyList()))
        `when`(services.aggregate(42, month)).thenReturn(emptyMap())
        `when`(expenses.forMonth(42, month.toString())).thenReturn(listOf(expense))
        `when`(names.resolve(setOf(42L), "token")).thenReturn(mapOf(42L to "Customer"))
        `when`(mappings.findById(42)).thenReturn(Optional.of(AccountIdMapping(42, "cust-42", "ABCDEF", "synthetic")))
        val service = BillingRunService(invoices, profiles, mock(ServiceCatalogRepository::class.java),
            mock(AccountIdMappingService::class.java), mappings, occupancy, movement, services,
            mock(FreighAiInvoiceClient::class.java), charges, mock(WmsInternalCascadeClient::class.java),
            mock(TenantBillingDefaultsService::class.java), mock(TenantOperationalCostsService::class.java),
            mock(BillingRunCostSnapshotRepository::class.java), mock(MovementCostAdjustmentService::class.java),
            generator, names, expenses, mock(SupplierExpenseSyncService::class.java))
        ReflectionTestUtils.setField(service, "aedCurrencyId", "CUR-AED")
        assertEquals(1, service.generate(42, month.toString(), "manager", "token").size)
        val invoice = requireNotNull(generatedInvoice)
        assertEquals("PROJECT_A", invoice.projectCode)
        assertTrue(invoice.storageLines.isEmpty() && invoice.movementLines.isEmpty() && invoice.serviceLines.isEmpty())
        assertEquals(0, invoice.grandTotal.signum())
        assertEquals(SnapshotSourceType.SUPPLIER_EXPENSE, generatedCosts.single().sourceType)
        assertEquals(BigDecimal("300.00"), generatedCosts.single().totalCost)
        assertEquals(0, generatedCosts.single().revenueAmount.signum())
    }
}
