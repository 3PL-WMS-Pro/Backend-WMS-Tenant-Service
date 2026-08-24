package com.wmspro.tenant.billing.invoice.edit

import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.common.external.freighai.client.InvoiceUpdateResult
import com.wmspro.common.external.freighai.client.InvoiceAllocationMutationResult
import com.wmspro.common.external.freighai.client.RevertResult
import com.wmspro.common.external.freighai.client.SendResult
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceLineItemResponse
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceResponse
import com.wmspro.common.external.freighai.dto.UpdateFreighAiInvoiceRequest
import com.wmspro.common.external.freighai.dto.FreighAiCurrencyEmbed
import com.wmspro.common.external.freighai.dto.ReplaceFreighAiJobAllocationsResponse
import com.wmspro.common.external.freighai.dto.ReplaceFreighAiJobAllocationsRequest
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import java.math.BigDecimal
import java.util.Optional

/**
 * Unit tests for [WmsInvoiceEditService].
 *
 * The bar these guard is narrow but high: this is the one place in WMS where a
 * human rewrites a financial document, so the tests concentrate on the refusals
 * (removal, all-zero, wrong status, stale view) and on the two FreighAi
 * sequences — plain update vs revert → update → re-send — rather than on happy
 * arithmetic, which FreighAi owns.
 */
class WmsInvoiceEditServiceTest {

    private lateinit var repository: WmsBillingInvoiceRepository
    private lateinit var client: FreighAiInvoiceClient
    private lateinit var service: WmsInvoiceEditService

    private val invoiceId = "wmsinv_test001"
    private val freighaiId = "INV-1"
    private val token = "Bearer t"
    private val user = "admin@example.com"

    @BeforeEach
    fun setUp() {
        repository = Mockito.mock(WmsBillingInvoiceRepository::class.java)
        client = Mockito.mock(FreighAiInvoiceClient::class.java)
        service = WmsInvoiceEditService(repository, client)
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private fun wmsInvoice(
        status: BillingInvoiceStatus = BillingInvoiceStatus.SUBMITTED,
        freighaiInvoiceId: String? = freighaiId
    ) = WmsBillingInvoice(
        billingInvoiceId = invoiceId,
        customerId = 1L,
        billingMonth = "2026-04",
        status = status,
        freighaiInvoiceId = freighaiInvoiceId,
        freighaiReferenceNo = "WMS-1-default-2026-04",
        subtotal = BigDecimal("100.00"),
        totalVat = BigDecimal("5.00"),
        grandTotal = BigDecimal("105.00")
    )

    private fun freighaiLine(
        lineNo: Int,
        desc: String = "Storage – April 2026",
        qty: String = "10",
        price: String = "10.00",
        chargeTypeId: String? = "CHG-1"
    ) = FreighAiInvoiceLineItemResponse(
        lineNo = lineNo,
        lineId = "line-$lineNo",
        description = desc,
        quantity = BigDecimal(qty),
        unit = "CBM-day",
        unitPrice = BigDecimal(price),
        amount = BigDecimal(qty).multiply(BigDecimal(price)),
        chargeTypeId = chargeTypeId,
        chargeTypeLabel = "Storage",
        vatPercent = BigDecimal("5"),
        vatAmount = BigDecimal("5.00"),
        ledgerId = "LED-4001.01"
    )

    private fun freighaiInvoice(
        status: String = "DRAFT",
        lines: List<FreighAiInvoiceLineItemResponse> = listOf(freighaiLine(1))
    ) = FreighAiInvoiceResponse(
        invoiceId = freighaiId,
        invoiceNo = "SI-0001",
        currentStatus = status,
        grandTotal = BigDecimal("105.00"),
        subtotal = BigDecimal("100.00"),
        totalVatAmount = BigDecimal("5.00"),
        lineItems = lines
    )

    private fun editLine(
        lineNo: Int?,
        desc: String = "Storage – April 2026",
        qty: String = "10",
        price: String = "10.00",
        chargeTypeId: String? = "CHG-1"
    ) = EditLineRequest(
        lineNo = lineNo,
        description = desc,
        quantity = BigDecimal(qty),
        unit = "CBM-day",
        unitPrice = BigDecimal(price),
        chargeTypeId = chargeTypeId,
        chargeTypeLabel = "Storage",
        vatPercent = BigDecimal("5")
    )

    /**
     * Kotlin/Mockito bridges. Mockito's matchers return Java platform types
     * that are null at match-registration time; Kotlin inserts a null check
     * when they feed a non-null parameter, so both need wrapping.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyObj(): T {
        Mockito.any<T>()
        return null as T
    }

    /** Registers an `eq` matcher but hands Kotlin back the real, non-null value. */
    private fun <T : Any> eqv(value: T): T {
        Mockito.eq(value)
        return value
    }

    /** Same bridge for captors — `capture()` is null at registration time. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> capt(captor: ArgumentCaptor<T>): T {
        captor.capture()
        return null as T
    }

    private fun currentVersion(inv: FreighAiInvoiceResponse): String =
        service.getEditableView(invoiceId, token).baseVersion

    private fun stubFetch(inv: FreighAiInvoiceResponse) {
        Mockito.`when`(repository.findById(invoiceId)).thenReturn(Optional.of(wmsInvoice()))
        Mockito.`when`(client.getInvoice(freighaiId, token)).thenReturn(inv)
    }

    // ── refusals ────────────────────────────────────────────────────────

    @Test
    fun `rejects a blank reason`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1)), "   ", "v"),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("reason")
    }

    @Test
    fun `rejects removal of an existing line`() {
        val inv = freighaiInvoice(lines = listOf(freighaiLine(1), freighaiLine(2, desc = "Handling")))
        stubFetch(inv)
        val version = currentVersion(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1)), "dropping handling", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("cannot be removed")
            .hasMessageContaining("Handling")

        Mockito.verify(client, Mockito.never()).updateInvoice(anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `rejects an edit that zeroes every line`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1, price = "0")), "waive everything", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("at least one billable line")

        Mockito.verify(client, Mockito.never()).updateInvoice(anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `rejects a billable line with no charge type`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(
                    listOf(editLine(1), editLine(null, desc = "Ad-hoc", chargeTypeId = null)),
                    "adding a one-off", version
                ),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("charge type")
    }

    @Test
    fun `rejects a stale view`() {
        val inv = freighaiInvoice()
        stubFetch(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1)), "late edit", "stale-version"),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("changed since you opened it")
    }

    @Test
    fun `rejects a duplicated line number`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1), editLine(1)), "oops", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("more than once")
    }

    @Test
    fun `refuses a paid invoice and points at credit notes`() {
        val inv = freighaiInvoice(status = "PAID")
        stubFetch(inv)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1)), "too late", "any"),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("credit note")
    }

    @Test
    fun `refuses an invoice that is not SUBMITTED in WMS`() {
        Mockito.`when`(repository.findById(invoiceId))
            .thenReturn(Optional.of(wmsInvoice(status = BillingInvoiceStatus.CANCELLED)))

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1)), "no", "any"),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("CANCELLED")
    }

    // ── happy paths ─────────────────────────────────────────────────────

    @Test
    fun `edits a DRAFT invoice without reverting`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        val updated = freighaiInvoice(lines = listOf(freighaiLine(1, qty = "12")))
            .copy(grandTotal = BigDecimal("126.00"), subtotal = BigDecimal("120.00"))
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(updated))
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "corrected CBM", version),
            user, token
        )

        assertThat(result.revertCycleRan).isFalse()
        assertThat(result.grandTotal).isEqualByComparingTo("126.00")
        Mockito.verify(client, Mockito.never()).revertToDraft(anyObj(), anyObj(), anyObj())
        Mockito.verify(client, Mockito.never()).sendInvoice(anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `edits a SENT invoice by reverting then re-sending`() {
        val inv = freighaiInvoice(status = "SENT")
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.revertToDraft(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(RevertResult.Success)
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(freighaiInvoice(status = "SENT")))
        Mockito.`when`(client.sendInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(SendResult.Success)
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "customer disputed", version),
            user, token
        )

        assertThat(result.revertCycleRan).isTrue()
        val order = Mockito.inOrder(client)
        order.verify(client).revertToDraft(eqv(freighaiId), anyObj(), eqv(token))
        order.verify(client).updateInvoice(eqv(freighaiId), anyObj(), eqv(token))
        order.verify(client).sendInvoice(eqv(freighaiId), anyObj(), eqv(token))
    }

    @Test
    fun `withholds zeroed lines from FreighAi but keeps them on the record`() {
        val inv = freighaiInvoice(lines = listOf(freighaiLine(1), freighaiLine(2, desc = "Handling")))
        stubFetch(inv)
        val version = currentVersion(inv)

        // Captured through the stub rather than an ArgumentCaptor: `capture()`
        // is null at registration time and `updateInvoice` takes a non-null
        // Kotlin parameter, so the captor form trips an inserted null check.
        var sent: UpdateFreighAiInvoiceRequest? = null
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenAnswer { call ->
                sent = call.arguments[1] as UpdateFreighAiInvoiceRequest
                InvoiceUpdateResult.Success(freighaiInvoice(lines = listOf(freighaiLine(1))))
            }
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(
                listOf(editLine(1), editLine(2, desc = "Handling", price = "0")),
                "waiving handling as agreed", version
            ),
            user, token
        )

        // FreighAi rejects zero-amount lines outright — it must never see one.
        assertThat(sent).isNotNull
        assertThat(sent!!.lineItems).hasSize(1)
        assertThat(sent!!.lineItems[0].description).isEqualTo("Storage – April 2026")

        assertThat(result.zeroedLineCount).isEqualTo(1)
        assertThat(result.lines.filter { it.isZeroed }).hasSize(1)
        assertThat(result.lines.first { it.isZeroed }.description).isEqualTo("Handling")
    }

    @Test
    fun `Warehouse draft edit sends document revision and rebuilds selling-line allocations`() {
        val sellingLineId = "0123456789abcdef0123456789abcdef"
        val local = wmsInvoice().copy(
            generationContractVersion = "WAREHOUSE_JOB_V1",
            warehouseJobId = "WJ-1",
            warehouseJobCurrencyCode = "AED"
        )
        val warehouseLine = freighaiLine(1).copy(
            lineId = sellingLineId,
            warehouseAccountingCategory = "WAREHOUSE_STORAGE"
        )
        val before = freighaiInvoice(lines = listOf(warehouseLine)).copy(
            documentRevision = 7,
            allocationRevision = 1,
            currency = FreighAiCurrencyEmbed("AED")
        )
        val after = freighaiInvoice(lines = listOf(warehouseLine.copy(
            quantity = BigDecimal("12"),
            amount = BigDecimal("120")
        ))).copy(
            documentRevision = 8,
            allocationRevision = 0,
            currency = FreighAiCurrencyEmbed("AED")
        )
        Mockito.`when`(repository.findById(invoiceId)).thenReturn(Optional.of(local))
        Mockito.`when`(client.getInvoice(freighaiId, token)).thenReturn(before)
        val version = currentVersion(before)
        var updateRequest: UpdateFreighAiInvoiceRequest? = null
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token))).thenAnswer {
            updateRequest = it.arguments[1] as UpdateFreighAiInvoiceRequest
            InvoiceUpdateResult.Success(after)
        }
        var allocationRequest: ReplaceFreighAiJobAllocationsRequest? = null
        Mockito.`when`(client.replaceJobAllocationsV1(eqv(freighaiId), anyObj(), anyObj(), eqv(token), anyObj()))
            .thenAnswer {
                allocationRequest = it.arguments[1] as ReplaceFreighAiJobAllocationsRequest
                InvoiceAllocationMutationResult.Success(
                    ReplaceFreighAiJobAllocationsResponse(freighaiId, 1, BigDecimal("126"), BigDecimal.ZERO)
                )
            }
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>())).thenAnswer { it.arguments[0] }

        service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(
                listOf(editLine(1, qty = "12").copy(
                    lineId = sellingLineId,
                    warehouseAccountingCategory = "WAREHOUSE_STORAGE"
                )),
                "corrected quantity",
                version
            ),
            user,
            token
        )

        assertThat(updateRequest!!.expectedDocumentRevision).isEqualTo(7)
        val allocation = allocationRequest!!.allocations.single()
        assertThat(allocation.jobId).isEqualTo("WJ-1")
        assertThat(allocation.invoiceLineId).isEqualTo(sellingLineId)
        assertThat(allocation.jobLineId).isEqualTo(sellingLineId)
        assertThat(allocation.netAmount).isEqualByComparingTo("120")
    }

    @Test
    fun `Warehouse draft edit assigns a stable manual line and allocates it to the job only`() {
        val sellingLineId = "0123456789abcdef0123456789abcdef"
        val original = freighaiLine(1).copy(
            lineId = sellingLineId,
            warehouseAccountingCategory = "WAREHOUSE_STORAGE"
        )
        val local = wmsInvoice().copy(
            generationContractVersion = "WAREHOUSE_JOB_V1",
            warehouseJobId = "WJ-1",
            warehouseJobCurrencyCode = "AED"
        )
        val before = freighaiInvoice(lines = listOf(original)).copy(
            documentRevision = 3,
            allocationRevision = 1,
            currency = FreighAiCurrencyEmbed("AED")
        )
        Mockito.`when`(repository.findById(invoiceId)).thenReturn(Optional.of(local))
        Mockito.`when`(client.getInvoice(freighaiId, token)).thenReturn(before)
        val version = currentVersion(before)

        var updateRequest: UpdateFreighAiInvoiceRequest? = null
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token))).thenAnswer { call ->
            updateRequest = call.arguments[1] as UpdateFreighAiInvoiceRequest
            val manual = updateRequest!!.lineItems[1]
            InvoiceUpdateResult.Success(
                freighaiInvoice(lines = listOf(
                    original,
                    freighaiLine(2, desc = "Weekend labour", qty = "2", price = "25").copy(
                        lineId = manual.lineId,
                        amount = BigDecimal("50"),
                        vatAmount = BigDecimal("2.50"),
                        warehouseAccountingCategory = manual.warehouseAccountingCategory
                    )
                )).copy(
                    documentRevision = 4,
                    allocationRevision = 0,
                    currency = FreighAiCurrencyEmbed("AED")
                )
            )
        }
        var allocationRequest: ReplaceFreighAiJobAllocationsRequest? = null
        Mockito.`when`(client.replaceJobAllocationsV1(eqv(freighaiId), anyObj(), anyObj(), eqv(token), anyObj()))
            .thenAnswer { call ->
                allocationRequest = call.arguments[1] as ReplaceFreighAiJobAllocationsRequest
                InvoiceAllocationMutationResult.Success(
                    ReplaceFreighAiJobAllocationsResponse(freighaiId, 1, BigDecimal("150"), BigDecimal("7.50"))
                )
            }
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>())).thenAnswer { it.arguments[0] }

        service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(
                listOf(
                    editLine(1).copy(
                        lineId = sellingLineId,
                        warehouseAccountingCategory = "WAREHOUSE_STORAGE"
                    ),
                    editLine(null, desc = "Weekend labour", qty = "2", price = "25").copy(
                        warehouseAccountingCategory = "WAREHOUSE_SERVICE"
                    )
                ),
                "added approved weekend work",
                version
            ),
            user,
            token
        )

        val manualLine = updateRequest!!.lineItems[1]
        assertThat(manualLine.lineId).startsWith("iline_")
        assertThat(manualLine.warehouseAccountingCategory).isEqualTo("WAREHOUSE_SERVICE")
        val manualAllocation = allocationRequest!!.allocations.single {
            it.invoiceLineId == manualLine.lineId
        }
        assertThat(manualAllocation.jobId).isEqualTo("WJ-1")
        assertThat(manualAllocation.jobLineId).isNull()
    }

    @Test
    fun `restores SENT state when the update is rejected after a revert`() {
        val inv = freighaiInvoice(status = "SENT")
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.revertToDraft(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(RevertResult.Success)
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Failure("fiscal period 2026-4 is CLOSED"))
        Mockito.`when`(client.sendInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(SendResult.Success)

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "late fix", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("CLOSED")

        // The invoice must not be left sitting in DRAFT after a failed edit.
        Mockito.verify(client).sendInvoice(eqv(freighaiId), anyObj(), eqv(token))
        Mockito.verify(repository, Mockito.never()).save(anyObj<WmsBillingInvoice>())
    }

    @Test
    fun `surfaces a refused revert without touching the invoice`() {
        val inv = freighaiInvoice(status = "SENT")
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.revertToDraft(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(RevertResult.Failure("payment already allocated"))

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "too late", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("payment already allocated")

        Mockito.verify(client, Mockito.never()).updateInvoice(anyObj(), anyObj(), anyObj())
    }

    @Test
    fun `records the reason and a field-level diff`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(freighaiInvoice(lines = listOf(freighaiLine(1, qty = "12")))))
        val saved = ArgumentCaptor.forClass(WmsBillingInvoice::class.java)
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "corrected CBM per survey", version),
            user, token
        )

        Mockito.verify(repository).save(capt(saved))
        val entry = saved.value.editHistory.single()
        assertThat(saved.value.manuallyEdited).isTrue()
        assertThat(entry.reason).isEqualTo("corrected CBM per survey")
        assertThat(entry.editedBy).isEqualTo(user)
        assertThat(entry.changes).anySatisfy {
            assertThat(it.field).isEqualTo("quantity")
            assertThat(it.oldValue).isEqualTo("10")
            assertThat(it.newValue).isEqualTo("12")
        }
    }

    @Test
    fun `does not compensate when the update outcome is unknown`() {
        val inv = freighaiInvoice(status = "SENT")
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.revertToDraft(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(RevertResult.Success)
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Indeterminate("Transport error: read timed out"))

        assertThatThrownBy {
            service.applyEdit(
                invoiceId,
                EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "timeout case", version),
                user, token
            )
        }
            .isInstanceOf(InvoiceEditConflictException::class.java)
            .hasMessageContaining("unclear whether your changes were applied")

        // Re-sending here could re-issue a half-applied invoice — the whole
        // point is that we do not guess.
        Mockito.verify(client, Mockito.never()).sendInvoice(anyObj(), anyObj(), anyObj())
        Mockito.verify(repository, Mockito.never()).save(anyObj<WmsBillingInvoice>())
    }

    @Test
    fun `reports a warning when the invoice cannot be re-issued after editing`() {
        val inv = freighaiInvoice(status = "SENT")
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.revertToDraft(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(RevertResult.Success)
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(freighaiInvoice(status = "DRAFT")))
        Mockito.`when`(client.sendInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(SendResult.Failure("period closed"))
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(listOf(editLine(1, qty = "12")), "dispute", version),
            user, token
        )

        // The edit applied, so this is not an error — but the caller must be
        // told the customer can no longer see the invoice.
        assertThat(result.revertCycleRan).isFalse()
        assertThat(result.warning).contains("could not be re-issued")
        assertThat(result.warning).contains("draft")
    }

    @Test
    fun `flags a hand-added billable line as manual`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        val withAdded = freighaiInvoice(
            lines = listOf(freighaiLine(1), freighaiLine(2, desc = "Crane hire", chargeTypeId = "CHG-9"))
        )
        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(withAdded))
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        val result = service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(
                listOf(editLine(1), editLine(null, desc = "Crane hire", chargeTypeId = "CHG-9")),
                "agreed one-off charge", version
            ),
            user, token
        )

        val added = result.lines.first { it.description == "Crane hire" }
        assertThat(added.isManual).isTrue()
        assertThat(result.lines.first { it.description == "Storage – April 2026" }.isManual).isFalse()
    }

    @Test
    fun `baseVersion changes when only a description changes`() {
        // Totals stay identical, so a token built from amounts alone would let
        // two admins silently overwrite each other.
        val a = freighaiInvoice(lines = listOf(freighaiLine(1, desc = "Storage")))
        val b = freighaiInvoice(lines = listOf(freighaiLine(1, desc = "Storage (revised)")))

        Mockito.`when`(repository.findById(invoiceId)).thenReturn(Optional.of(wmsInvoice()))
        Mockito.`when`(client.getInvoice(freighaiId, token)).thenReturn(a, b)

        val first = service.getEditableView(invoiceId, token).baseVersion
        val second = service.getEditableView(invoiceId, token).baseVersion
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `records unit and charge type changes in the audit diff`() {
        val inv = freighaiInvoice()
        stubFetch(inv)
        val version = currentVersion(inv)

        Mockito.`when`(client.updateInvoice(eqv(freighaiId), anyObj(), eqv(token)))
            .thenReturn(InvoiceUpdateResult.Success(freighaiInvoice()))
        val saved = ArgumentCaptor.forClass(WmsBillingInvoice::class.java)
        Mockito.`when`(repository.save(anyObj<WmsBillingInvoice>()))
            .thenAnswer { it.arguments[0] as WmsBillingInvoice }

        service.applyEdit(
            invoiceId,
            EditInvoiceLinesRequest(
                listOf(editLine(1).copy(unit = "pallet", chargeTypeId = "CHG-2")),
                "re-categorised", version
            ),
            user, token
        )

        Mockito.verify(repository).save(capt(saved))
        val fields = saved.value.editHistory.single().changes.map { it.field }
        assertThat(fields).contains("unit", "chargeTypeId")
    }

    // ── read surface ────────────────────────────────────────────────────

    @Test
    fun `editable view blocks a cancelled invoice`() {
        stubFetch(freighaiInvoice(status = "CANCELLED"))
        val view = service.getEditableView(invoiceId, token)
        assertThat(view.editable).isFalse()
        assertThat(view.blockedReason).contains("cancelled")
    }

    @Test
    fun `editable view flags that a SENT invoice needs a revert`() {
        stubFetch(freighaiInvoice(status = "SENT"))
        val view = service.getEditableView(invoiceId, token)
        assertThat(view.editable).isTrue()
        assertThat(view.requiresRevert).isTrue()
    }

    @Test
    fun `editable view degrades gracefully when FreighAi is unreachable`() {
        Mockito.`when`(repository.findById(invoiceId)).thenReturn(Optional.of(wmsInvoice()))
        Mockito.`when`(client.getInvoice(freighaiId, token)).thenReturn(null)

        val view = service.getEditableView(invoiceId, token)
        assertThat(view.editable).isFalse()
        assertThat(view.blockedReason).contains("unreachable")
    }
}
