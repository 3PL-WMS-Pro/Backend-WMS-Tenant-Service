package com.wmspro.tenant.billing.adjustment

import com.wmspro.tenant.billing.snapshot.*
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

enum class SupplierExpenseState { OPEN, FROZEN, SYNCED }

/** A total net supplier cost, never a per-CBM adjustment or customer selling line. */
@Document(collection = "supplier_expenses")
@org.springframework.data.mongodb.core.index.CompoundIndexes(
    org.springframework.data.mongodb.core.index.CompoundIndex(name = "expense_record", def = "{'attachedTo.id':1,'createdAt':1}"),
    org.springframework.data.mongodb.core.index.CompoundIndex(name = "expense_period", def = "{'customerId':1,'billingMonth':1,'projectCode':1}"),
    org.springframework.data.mongodb.core.index.CompoundIndex(name = "expense_retry", def = "{'state':1,'nextSyncAt':1}")
)
data class SupplierExpense(
    @Id val expenseId: String,
    val customerId: Long,
    val attachedTo: AdjustmentAttachedRef,
    val projectCode: String?,
    val incurredOn: LocalDate,
    val billingMonth: String,
    val description: String,
    val netAmount: BigDecimal,
    val currencyCode: String = "AED",
    val chargeTypeId: String,
    val supplierName: String? = null,
    val notes: String? = null,
    val revision: Long = 0,
    val state: SupplierExpenseState = SupplierExpenseState.OPEN,
    val billingInvoiceId: String? = null,
    val warehouseJobId: String? = null,
    val lastSyncError: String? = null,
    val nextSyncAt: Instant = Instant.EPOCH,
    val createdBy: String,
    val createdAt: Instant = Instant.now(),
    val updatedBy: String = createdBy,
    val updatedAt: Instant = createdAt
) {
    @get:org.springframework.data.annotation.Transient
    val costLineId: String get() = "supplier-$expenseId"

    fun snapshot(invoiceId: String) = BillingRunCostSnapshot(
        snapshotId = "supplier-$expenseId", billingInvoiceId = invoiceId,
        customerId = customerId, billingMonth = billingMonth,
        sourceType = SnapshotSourceType.SUPPLIER_EXPENSE,
        sourceRecord = SnapshotRef(SnapshotSourceType.SUPPLIER_EXPENSE, expenseId, description),
        projectCode = projectCode, quantity = BigDecimal.ONE, unit = "EXPENSE",
        baseCostRate = netAmount, effectiveCostRate = netAmount, totalCost = netAmount,
        revenueRate = BigDecimal.ZERO, revenueAmount = BigDecimal.ZERO, margin = netAmount.negate(),
        costLineId = costLineId, sourceLineId = revision.toString(),
        costTreatment = "PARTNER_INVOICE", freighaiChargeTypeId = chargeTypeId,
        completionWeight = netAmount, calculationVersion = "WMS_COST_V1"
    )
}

data class SaveSupplierExpenseRequest(
    val expenseId: String,
    val customerId: Long,
    val attachedTo: AdjustmentAttachedRef,
    val incurredOn: LocalDate,
    val description: String,
    val netAmount: BigDecimal,
    val chargeTypeId: String,
    val currencyCode: String = "AED",
    val supplierName: String? = null,
    val notes: String? = null,
    val expectedRevision: Long = 0
)
