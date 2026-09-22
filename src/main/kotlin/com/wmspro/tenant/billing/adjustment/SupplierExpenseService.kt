package com.wmspro.tenant.billing.adjustment

import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

@Service
class SupplierExpenseService(private val mongo: MongoTemplate, private val chargeTypes: FreighAiChargeTypeClient) {
    fun list(recordId: String): List<SupplierExpense> = mongo.find(
        Query.query(Criteria.where("attachedTo.id").`is`(recordId)), SupplierExpense::class.java)

    fun forMonth(customerId: Long, month: String): List<SupplierExpense> = mongo.find(
        Query.query(Criteria.where("customerId").`is`(customerId).and("billingMonth").`is`(month)), SupplierExpense::class.java)

    fun get(id: String): SupplierExpense = mongo.findById(id, SupplierExpense::class.java)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier expense not found")

    fun save(request: SaveSupplierExpenseRequest, actor: String, token: String, editing: Boolean): SupplierExpense {
        require(request.expenseId.matches(Regex("[a-zA-Z0-9-]{8,80}"))) { "A stable expense ID is required" }
        require(request.description.isNotBlank() && request.description.length <= 200) { "Description is required (maximum 200 characters)" }
        require(request.netAmount > BigDecimal.ZERO && request.netAmount.stripTrailingZeros().scale() <= 2) { "Enter a positive total net amount with at most two decimal places" }
        require(request.currencyCode == "AED") { "Supplier expenses currently use the WMS billing currency AED" }
        require(!request.incurredOn.isAfter(LocalDate.now())) { "Expense date cannot be in the future" }
        require((request.notes?.length ?: 0) <= 1000 && (request.supplierName?.length ?: 0) <= 200) { "Supplier or notes are too long" }
        require(chargeTypes.listChargeTypes(token, activeOnly = true).any { it.chargeTypeId == request.chargeTypeId }) { "Choose an active FreighAI Charge Type" }
        val collection = if (request.attachedTo.type == AdjustmentAttachedType.GRN) "receiving_records" else "order_fulfillment_requests"
        val source = mongo.findById(request.attachedTo.id, Document::class.java, collection)
            ?: throw IllegalArgumentException("Attached warehouse record does not exist")
        require((source["accountId"] as? Number)?.toLong() == request.customerId) { "Warehouse record belongs to a different customer" }
        val project = (source["projectCode"] as? String)?.takeIf(String::isNotBlank)
        val number = (source[if (request.attachedTo.type == AdjustmentAttachedType.GRN) "receivingRecordNumber" else "fulfillmentNumber"] as? String) ?: request.attachedTo.id
        val old = mongo.findById(request.expenseId, SupplierExpense::class.java)
        val candidate = SupplierExpense(
            expenseId = request.expenseId, customerId = request.customerId,
            attachedTo = request.attachedTo.copy(number = number), projectCode = project,
            incurredOn = request.incurredOn, billingMonth = YearMonth.from(request.incurredOn).toString(),
            description = request.description.trim(), netAmount = request.netAmount.setScale(2),
            chargeTypeId = request.chargeTypeId, supplierName = request.supplierName?.trim()?.takeIf(String::isNotBlank),
            notes = request.notes?.trim()?.takeIf(String::isNotBlank), createdBy = actor)
        if (!editing) {
            if (old != null) {
                if (!sameInput(old, candidate)) conflict("Expense ID was already used with different data")
                return old
            }
            return try { mongo.insert(candidate) } catch (_: org.springframework.dao.DuplicateKeyException) {
                val winner = get(candidate.expenseId)
                if (!sameInput(winner, candidate)) conflict("Expense ID was already used with different data")
                winner
            }
        }
        if (old == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier expense not found")
        require(old.customerId == candidate.customerId && old.attachedTo.id == candidate.attachedTo.id && old.attachedTo.type == candidate.attachedTo.type) { "Expense source cannot be changed" }
        val update = Update().set("description", candidate.description).set("netAmount", candidate.netAmount)
            .set("incurredOn", candidate.incurredOn).set("billingMonth", candidate.billingMonth)
            .set("projectCode", candidate.projectCode).set("chargeTypeId", candidate.chargeTypeId)
            .set("supplierName", candidate.supplierName).set("notes", candidate.notes)
            .set("updatedBy", actor).set("updatedAt", Instant.now()).inc("revision", 1)
        return mongo.findAndModify(openQuery(request.expenseId, request.expectedRevision), update,
            FindAndModifyOptions.options().returnNew(true), SupplierExpense::class.java)
            ?: conflict("Expense changed or is already frozen for billing; refresh before editing")
    }

    fun delete(id: String, revision: Long) {
        if (mongo.remove(openQuery(id, revision), SupplierExpense::class.java).deletedCount != 1L)
            conflict("Expense changed or is already frozen for billing; it cannot be deleted")
    }

    /** CAS fencing makes an edit racing with billing fail instead of changing frozen evidence. */
    fun freeze(snapshot: BillingRunCostSnapshot, invoiceId: String): SupplierExpense {
        val expense = get(snapshot.sourceRecord.id)
        if (expense.billingInvoiceId == invoiceId && expense.state != SupplierExpenseState.OPEN) return expense
        val collection = if (expense.attachedTo.type == AdjustmentAttachedType.GRN) "receiving_records" else "order_fulfillment_requests"
        val source = mongo.findById(expense.attachedTo.id, Document::class.java, collection)
            ?: conflict("Expense's warehouse record no longer exists")
        if ((source["accountId"] as? Number)?.toLong() != expense.customerId ||
            (source["projectCode"] as? String)?.takeIf(String::isNotBlank) != expense.projectCode)
            conflict("The warehouse record's customer or project changed. Update the expense before generating billing.")
        val revision = snapshot.sourceLineId?.toLongOrNull() ?: error("Expense revision missing")
        return mongo.findAndModify(openQuery(expense.expenseId, revision),
            Update().set("state", SupplierExpenseState.FROZEN).set("billingInvoiceId", invoiceId).set("updatedAt", Instant.now()),
            FindAndModifyOptions.options().returnNew(true), SupplierExpense::class.java)
            ?: conflict("Supplier expense changed while billing was being generated; preview again")
    }

    fun release(invoiceId: String) {
        mongo.updateMulti(Query.query(Criteria.where("billingInvoiceId").`is`(invoiceId).and("state").`is`(SupplierExpenseState.FROZEN).and("warehouseJobId").`is`(null)),
            Update().set("state", SupplierExpenseState.OPEN).unset("billingInvoiceId"), SupplierExpense::class.java)
    }

    private fun openQuery(id: String, revision: Long) = Query.query(Criteria.where("expenseId").`is`(id)
        .and("state").`is`(SupplierExpenseState.OPEN).and("revision").`is`(revision))
    private fun sameInput(a: SupplierExpense, b: SupplierExpense) =
        a.customerId == b.customerId && a.attachedTo == b.attachedTo && a.incurredOn == b.incurredOn &&
        a.description == b.description && a.netAmount.compareTo(b.netAmount) == 0 && a.currencyCode == b.currencyCode &&
        a.chargeTypeId == b.chargeTypeId && a.supplierName == b.supplierName && a.notes == b.notes
    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
}
