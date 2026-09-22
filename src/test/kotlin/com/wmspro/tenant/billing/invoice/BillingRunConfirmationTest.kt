package com.wmspro.tenant.billing.invoice

import com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant

class BillingRunConfirmationTest {
    private val service = mock(BillingRunService::class.java)
    private val invoices = mock(WmsBillingInvoiceRepository::class.java)
    private val summaries = mock(BillingRunSummaryRepository::class.java)
    private val controller = BillingRunController(service, invoices, mock(CustomerBillingProfileRepository::class.java), summaries, mock(CustomerNameResolver::class.java))
    private val http = MockHttpServletRequest().apply { addHeader("X-User-Email", "manager@example.com") }

    @Test
    fun `existing invoice requires deliberate confirmation even outside paginated history`() {
        `when`(invoices.findByBillingMonth("2026-01")).thenReturn(listOf(invoice()))
        val result = controller.generateAll(GenerateAllBillingRunsRequest("2026-01"), http)
        assertEquals(409, result.statusCode.value())
        verifyNoInteractions(service)
    }

    @Test
    fun `customer check does not warn because another customer was billed`() {
        `when`(invoices.findByBillingMonth("2026-01")).thenReturn(listOf(invoice()))
        assertEquals(false, controller.historyCheck("2026-01", 43).body!!.data!!["previouslyRun"])
        assertEquals(true, controller.historyCheck("2026-01", 42).body!!.data!!["previouslyRun"])
    }

    @Test
    fun `confirmed rerun reaches generator once for the selected customer`() {
        `when`(service.generate(42, "2026-01", "manager@example.com", "")).thenReturn(listOf(invoice()))
        val result = controller.generate(GenerateBillingRunRequest(42, "2026-01", confirmRerun = true), http)
        assertEquals(201, result.statusCode.value())
        verify(service, times(1)).generate(42, "2026-01", "manager@example.com", "")
    }

    private fun invoice() = WmsBillingInvoice(billingInvoiceId = "inv-1", customerId = 42, billingMonth = "2026-01",
        status = BillingInvoiceStatus.SUBMITTED, freighaiReferenceNo = "ref-1", generatedAt = Instant.EPOCH)
}
