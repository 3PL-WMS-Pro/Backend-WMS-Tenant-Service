package com.wmspro.tenant.billing.warehousejob

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.wmspro.common.external.freighai.client.*
import com.wmspro.common.external.freighai.dto.*
import com.wmspro.tenant.billing.invoice.*
import com.wmspro.tenant.billing.warehousejob.orchestration.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.test.util.ReflectionTestUtils
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Optional

class WarehouseSalesInvoiceOutboxHandlerTest {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Test
    fun `creates one generic SI with stable revenue allocations and scoped accounting categories`() {
        val outbox = Mockito.mock(WarehouseJobOutboxRepository::class.java)
        val finance = Mockito.mock(FreighAiInvoiceClient::class.java)
        val binding = Mockito.mock(WarehouseJobBindingService::class.java)
        val handler = WarehouseSalesInvoiceOutboxHandler(mapper, outbox, finance, binding)
        ReflectionTestUtils.setField(handler, "currencyId", "CUR-AED")
        val local = invoice()
        val wj = warehouseRequest()
        val predecessor = WarehouseJobOutbox(
            outboxId = "wj-1", billingInvoiceId = local.billingInvoiceId, payloadVersion = 1,
            operation = WarehouseJobOutboxOperation.UPSERT_WJ, idempotencyKey = "wj-key",
            payload = mapper.writeValueAsString(wj), payloadHash = "hash", correlationId = "corr",
            state = WarehouseJobOutboxState.SUCCEEDED
        )
        val command = WarehouseJobOutbox(
            outboxId = "si-1", billingInvoiceId = local.billingInvoiceId, payloadVersion = 1,
            operation = WarehouseJobOutboxOperation.CREATE_OR_LINK_SI, idempotencyKey = "si-key",
            dependencyOutboxId = predecessor.outboxId,
            payload = mapper.writeValueAsString(mapOf("externalReference" to "WMS-SI-wmsinv-1-V1")),
            payloadHash = "si-hash", correlationId = "corr"
        )
        val remote = FreighAiInvoiceResponse(
            invoiceId = "INV-1", invoiceNo = "SI-1", invoiceType = "SALES",
            invoiceDate = LocalDate.of(2026, 9, 30), currentStatus = "DRAFT",
            sourceSystem = "WMS", externalReference = "WMS-SI-wmsinv-1-V1",
            jobLinkContractVersion = "GENERIC_JOB_V1",
            linkedJobs = listOf(FreighAiLinkedJob("WJ-1", "WAREHOUSE")),
            currencyId = "CUR-AED", currency = FreighAiCurrencyEmbed("AED")
        )
        Mockito.`when`(binding.load(command)).thenReturn(local)
        Mockito.`when`(outbox.findById(predecessor.outboxId)).thenReturn(Optional.of(predecessor))
        Mockito.`when`(finance.findInvoiceByExternalReference("WMS", "WMS-SI-wmsinv-1-V1", "jwt"))
            .thenReturn(InvoiceLookupResult.NotFound)
        var createdRequest: CreateFreighAiInvoiceRequest? = null
        Mockito.`when`(finance.createInvoiceV1(anyObj(), eqv("si-key"), eqv("jwt")))
            .thenAnswer {
                createdRequest = it.arguments[0] as CreateFreighAiInvoiceRequest
                InvoiceV1CreationResult.Success(remote)
            }
        Mockito.`when`(finance.getJobAllocationsV1("INV-1", "jwt"))
            .thenReturn(InvoiceAllocationLookupResult.Found(emptyList()))
        var allocationRequest: ReplaceFreighAiJobAllocationsRequest? = null
        Mockito.`when`(finance.replaceJobAllocationsV1(eqv("INV-1"), anyObj(), anyObj(), eqv("jwt"), anyObj()))
            .thenAnswer {
                allocationRequest = it.arguments[1] as ReplaceFreighAiJobAllocationsRequest
                InvoiceAllocationMutationResult.Success(
                ReplaceFreighAiJobAllocationsResponse("INV-1", 1, BigDecimal("42"), BigDecimal.ZERO)
                )
            }
        Mockito.`when`(binding.bindSalesInvoice(command, remote)).thenReturn(true)

        val result = handler.handle(command, "jwt")
        assertTrue(result is OutboxCommandResult.Success)
        result as OutboxCommandResult.Success
        assertEquals("INV-1", result.remoteEntityId)
        assertTrue(result.remoteResponseHash.matches(Regex("^[0-9a-f]{64}$")))

        val capturedCreate = requireNotNull(createdRequest)
        assertEquals("GENERIC_JOB_V1", capturedCreate.jobLinkContractVersion)
        assertEquals("WAREHOUSING", capturedCreate.purpose)
        assertEquals(
            listOf("WAREHOUSE_STORAGE", "WAREHOUSE_INBOUND", "WAREHOUSE_OUTBOUND", "WAREHOUSE_SERVICE"),
            capturedCreate.lineItems.map { it.warehouseAccountingCategory }
        )
        assertTrue(requireNotNull(allocationRequest).allocations.all {
            it.target == "JOB" && it.jobId == "WJ-1" && it.jobLineId == it.invoiceLineId && it.costLineId == null
        })
    }

    private fun invoice() = WmsBillingInvoice(
        billingInvoiceId = "wmsinv-1", customerId = 7, billingMonth = "2026-09",
        status = BillingInvoiceStatus.DRAFT,
        storageLines = listOf(StorageLine(null, null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "Storage", "CHG-S")),
        movementLines = listOf(
            MovementLine(MovementDirection.INBOUND, null, null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "Inbound", "CHG-I"),
            MovementLine(MovementDirection.OUTBOUND, null, null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, "Outbound", "CHG-O")
        ),
        serviceLines = listOf(ServiceLine("PACK", "Packing", "item", BigDecimal.ONE, BigDecimal("12"), BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, "Packing", "CHG-P")),
        subtotal = BigDecimal("42"), grandTotal = BigDecimal("42"),
        freighaiReferenceNo = "WMS-7-default-2026-09", generatedAt = Instant.parse("2026-09-30T00:00:00Z"),
        generationContractVersion = "WAREHOUSE_JOB_V1", warehouseJobId = "WJ-1", warehouseJobNumber = "WJ-0001",
        warehouseJobPayloadVersion = 1, warehouseJobPayloadHash = "hash"
    )

    private fun warehouseRequest(): CreateFreighAiWarehouseJobRequest {
        val lines = listOf(
            selling("S", "Storage", "CHG-S", "10"), selling("I", "Inbound", "CHG-I", "10"),
            selling("O", "Outbound", "CHG-O", "10"), selling("P", "Packing", "CHG-P", "12")
        )
        return CreateFreighAiWarehouseJobRequest(
            externalReference = "WMS-7-default-2026-09", sourceRevision = 1, sourceContentHash = "hash",
            customerSnapshot = WarehouseCustomerSnapshot("CUS-7", "Customer"),
            warehouseContext = WarehouseContext(
                sourceTenantId = "7", wmsBillingInvoiceId = "wmsinv-1", wmsBillingReference = "ref",
                billingMonth = "2026-09", servicePeriodStart = LocalDate.of(2026, 9, 1),
                servicePeriodEnd = LocalDate.of(2026, 9, 30), projectBucket = "DEFAULT",
                sourceContentHash = "hash", chargeContentHash = "charge", calculationVersion = "V1"
            ),
            commercialSnapshot = WarehouseCommercialSnapshot(
                currencyCode = "AED", frozenAt = Instant.parse("2026-09-30T00:00:00Z"), sellingLines = lines,
                plannedCostLines = listOf(WarehouseJobPlannedCostLine("C", "Cost", treatment = "INTERNAL_STANDARD", quantity = BigDecimal.ONE, unit = "unit", unitCost = BigDecimal.ONE, plannedNetAmount = BigDecimal.ONE)),
                sellingSubtotal = BigDecimal("42"), taxTotal = BigDecimal.ZERO, sellingGrandTotal = BigDecimal("42"),
                plannedCostTotal = BigDecimal.ONE, plannedProfit = BigDecimal("41")
            )
        )
    }

    private fun selling(id: String, description: String, charge: String, amount: String) = WarehouseJobSellingLine(
        lineId = id, description = description, serviceCode = charge, quantity = BigDecimal.ONE,
        unit = "unit", unitPrice = BigDecimal(amount), netAmount = BigDecimal(amount),
        grossAmount = BigDecimal(amount)
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T { Mockito.any<T>(); return null as T }
    private fun <T : Any> eqv(value: T): T { Mockito.eq(value); return value }
}
