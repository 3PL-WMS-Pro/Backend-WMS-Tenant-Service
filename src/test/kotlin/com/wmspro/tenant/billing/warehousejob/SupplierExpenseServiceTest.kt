package com.wmspro.tenant.billing.warehousejob

import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.common.external.freighai.dto.FreighAiChargeType
import com.wmspro.tenant.billing.adjustment.*
import org.bson.Document
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate

class SupplierExpenseServiceTest {
    private val mongo = mock(MongoTemplate::class.java)
    private val charges = mock(FreighAiChargeTypeClient::class.java)
    private val service = SupplierExpenseService(mongo, charges)
    private val input = SaveSupplierExpenseRequest("expense-1234", 42,
        AdjustmentAttachedRef(AdjustmentAttachedType.GRN, "rr-1", "untrusted-number"),
        LocalDate.parse("2026-08-20"), "Forklift", BigDecimal("300.00"), "CHG-1")

    private fun source(customer: Long = 42) {
        `when`(charges.listChargeTypes("token", true)).thenReturn(listOf(FreighAiChargeType("CHG-1", "Forklift", BigDecimal.ZERO)))
        `when`(mongo.findById("rr-1", Document::class.java, "receiving_records"))
            .thenReturn(Document("accountId", customer).append("projectCode", "PROJECT_A").append("receivingRecordNumber", "GRN-0001"))
    }

    @Test
    fun `customer and project are checked against authoritative source data`() {
        source(43)
        assertThrows(IllegalArgumentException::class.java) { service.save(input, "manager", "token", false) }
        verify(mongo, never()).insert(any(SupplierExpense::class.java))
    }

    @Test
    fun `new expense uses source project and document number with independent net cost`() {
        source()
        `when`(mongo.insert(any(SupplierExpense::class.java))).thenAnswer { it.arguments[0] }
        val expense = service.save(input, "manager", "token", false)
        assertEquals("PROJECT_A", expense.projectCode)
        assertEquals("GRN-0001", expense.attachedTo.number)
        assertEquals("2026-08", expense.billingMonth)
        assertEquals(BigDecimal("300.00"), expense.netAmount)
        assertEquals(BigDecimal.ZERO, expense.snapshot("bill-1").revenueAmount)
        assertEquals(SupplierExpenseState.OPEN, expense.state)
    }

    @Test
    fun `negative totals and fractional cents are rejected before persistence`() {
        for (amount in listOf(BigDecimal("-1"), BigDecimal.ZERO, BigDecimal("1.001"))) {
            assertThrows(IllegalArgumentException::class.java) { service.save(input.copy(netAmount = amount), "manager", "token", false) }
        }
        verifyNoInteractions(mongo)
    }

    @Test
    fun `freeze refuses concurrent edit and includes original revision in CAS query`() {
        source()
        val expense = SupplierExpense("expense-1234", 42, input.attachedTo, "PROJECT_A", input.incurredOn,
            "2026-08", "Forklift", input.netAmount, chargeTypeId = "CHG-1", createdBy = "manager", revision = 2)
        `when`(mongo.findById(expense.expenseId, SupplierExpense::class.java)).thenReturn(expense)
        val staleSnapshot = expense.copy(revision = 1).snapshot("bill-1")
        val error = assertThrows(ResponseStatusException::class.java) { service.freeze(staleSnapshot, "bill-1") }
        assertEquals(409, error.statusCode.value())
        verify(mongo).findAndModify(argThat<Query> { it.queryObject["revision"] == 1L && it.queryObject["state"] == SupplierExpenseState.OPEN },
            any(Update::class.java), any(FindAndModifyOptions::class.java), eq(SupplierExpense::class.java))
    }
}
