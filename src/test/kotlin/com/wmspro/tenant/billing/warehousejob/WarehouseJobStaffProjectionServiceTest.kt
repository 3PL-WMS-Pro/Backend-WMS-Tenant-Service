package com.wmspro.tenant.billing.warehousejob

import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.external.freighai.client.FreighAiWarehouseJobClient
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.warehousejob.api.WarehouseJobStaffProjectionService
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobOutboxRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobRecoveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.util.ReflectionTestUtils

class WarehouseJobStaffProjectionServiceTest {
    @Test
    fun `summary uses frozen tenant currency and never invents AED`() {
        val service = WarehouseJobStaffProjectionService(
            Mockito.mock(MongoTemplate::class.java), Mockito.mock(WmsBillingInvoiceRepository::class.java),
            Mockito.mock(BillingRunCostSnapshotRepository::class.java), Mockito.mock(WarehouseJobOutboxRepository::class.java),
            Mockito.mock(WarehouseJobRecoveryService::class.java), Mockito.mock(FreighAiWarehouseJobClient::class.java),
            ObjectMapper()
        )
        val invoice = WmsBillingInvoice(
            billingInvoiceId = "wmsinv-eur", customerId = 1, billingMonth = "2026-09",
            status = BillingInvoiceStatus.DRAFT, freighaiReferenceNo = "ref",
            generationContractVersion = "WAREHOUSE_JOB_V1", warehouseJobCurrencyCode = "EUR",
            warehouseJobCustomerName = "Euro Customer"
        )

        @Suppress("UNCHECKED_CAST")
        val summary = ReflectionTestUtils.invokeMethod<Map<String, Any?>>(service, "summary", invoice)!!
        assertEquals("EUR", summary["currencyCode"])
        assertEquals("Euro Customer", summary["customerName"])
    }
}
