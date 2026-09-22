package com.wmspro.tenant.billing.adjustment

import com.wmspro.common.external.freighai.client.*
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.invoice.*
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

/** The immutable FROZEN expense is its own durable retry record. No token is persisted. */
@Service
class SupplierExpenseSyncService(
    private val expenses: SupplierExpenseService,
    private val invoices: WmsBillingInvoiceRepository,
    private val client: FreighAiWarehouseJobClient,
    private val mongo: MongoTemplate
) {
    fun syncMonth(invoice: WmsBillingInvoice, token: String) {
        expenses.forMonth(invoice.customerId, invoice.billingMonth)
            .filter { it.projectCode == invoice.projectCode && it.state != SupplierExpenseState.SYNCED }
            .forEach { expense ->
                try { sync(expense.expenseId, token) }
                catch (_: Exception) { error(expense, "Expense synchronization needs a retry. The billing run remains saved.") }
            }
    }

    fun retryPending(token: String) {
        mongo.find(Query.query(Criteria.where("state").ne(SupplierExpenseState.SYNCED).and("nextSyncAt").lte(Instant.now())).with(org.springframework.data.domain.Sort.by("nextSyncAt")).limit(50), SupplierExpense::class.java)
            .forEach { expense ->
                try { sync(expense.expenseId, token) }
                catch (_: Exception) { error(expense, "Expense synchronization needs a retry.") }
            }
    }

    fun sync(id: String, token: String): SupplierExpense {
        var expense = expenses.get(id)
        if (expense.state == SupplierExpenseState.SYNCED) return expense
        mongo.updateFirst(Query.query(Criteria.where("expenseId").`is`(id)),
            Update().set("nextSyncAt", Instant.now().plusSeconds(60)), SupplierExpense::class.java)
        val invoice = invoices.findByCustomerIdAndProjectCodeAndBillingMonth(expense.customerId, expense.projectCode, expense.billingMonth)
            ?: return expense // Included by a future billing run, even if its selling rate is zero.
        if (invoice.generationContractVersion != "WAREHOUSE_JOB_V1" || invoice.status == BillingInvoiceStatus.CANCELLED || invoice.warehouseJobStatus == "CANCELLED") {
            return error(expense, "This billing period has a legacy or cancelled job. A finance correction is required.")
        }
        val jobId = invoice.warehouseJobId ?: return error(expense, "Waiting for the Warehouse Job to synchronize. Retry after billing synchronization completes.")
        if (expense.currencyCode != invoice.warehouseJobCurrencyCode) return error(expense, "Expense currency does not match the Warehouse Job")
        expense = expenses.freeze(expense.snapshot(invoice.billingInvoiceId), invoice.billingInvoiceId)
        val payload = mapOf(
            "sourceTenantId" to requireNotNull(TenantContext.getCurrentTenant()),
            "currencyCode" to expense.currencyCode,
            "reason" to "Supplier expense ${expense.expenseId} from ${expense.attachedTo.type} ${expense.attachedTo.number}, incurred ${expense.incurredOn}",
            "costLine" to mapOf(
                "costLineId" to expense.costLineId, "description" to expense.description,
                "costCode" to "SUPPLIER_EXPENSE", "treatment" to "PARTNER_INVOICE",
                "completionWeight" to expense.netAmount, "quantity" to 1, "unit" to "EXPENSE",
                "unitCost" to expense.netAmount, "plannedNetAmount" to expense.netAmount,
                "chargeTypeId" to expense.chargeTypeId,
                "sourceReferences" to listOf(expense.expenseId, expense.revision.toString())
            )
        )
        when (val result = client.appendSupplierExpense(jobId, payload, expense.expenseId, token)) {
            is WarehouseJobMutationResult.Success -> {
                // insert, never replace: the original run's commercial evidence remains intact.
                val snapshot = expense.snapshot(invoice.billingInvoiceId).copy(generationContractVersion = "WAREHOUSE_JOB_V1")
                val existing = mongo.findOne(Query.query(Criteria.where("billingInvoiceId").`is`(invoice.billingInvoiceId)
                    .and("costLineId").`is`(expense.costLineId)), BillingRunCostSnapshot::class.java)
                if (existing == null) {
                    try { mongo.insert(snapshot) } catch (_: org.springframework.dao.DuplicateKeyException) {
                        val winner = mongo.findById(snapshot.snapshotId, BillingRunCostSnapshot::class.java)
                        check(winner?.billingInvoiceId == invoice.billingInvoiceId && winner.costLineId == expense.costLineId) { "Supplier expense snapshot identity conflict" }
                    }
                }
                mongo.updateFirst(Query.query(Criteria.where("expenseId").`is`(id).and("billingInvoiceId").`is`(invoice.billingInvoiceId)),
                    Update().set("state", SupplierExpenseState.SYNCED).set("warehouseJobId", jobId)
                        .unset("lastSyncError").set("updatedAt", Instant.now()), SupplierExpense::class.java)
            }
            is WarehouseJobMutationResult.Rejected -> return error(expense, result.errorMessage)
            is WarehouseJobMutationResult.Indeterminate -> return error(expense, "Synchronization could not be confirmed. Retry the same expense; do not create it again.")
        }
        return expenses.get(id)
    }

    private fun error(expense: SupplierExpense, message: String): SupplierExpense {
        mongo.updateFirst(Query.query(Criteria.where("expenseId").`is`(expense.expenseId).and("state").ne(SupplierExpenseState.SYNCED)),
            Update().set("lastSyncError", message.take(500)), SupplierExpense::class.java)
        return expenses.get(expense.expenseId)
    }
}
