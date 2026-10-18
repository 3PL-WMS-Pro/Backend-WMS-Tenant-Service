package com.wmspro.tenant.billing.invoice

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Component

/**
 * Invoices discarded before they were ever sent, kept for the record.
 *
 * Discarding moves an unsent draft out of `wms_billing_invoice` so its month can be generated
 * again. Its cancelled Sales Invoice and Warehouse Job stay in FreighAI under the original
 * identity, so a regeneration takes a fresh one from how many were discarded before it.
 */
@Component
class DiscardedBillingInvoices(private val mongoTemplate: MongoTemplate) {

    fun archive(invoice: WmsBillingInvoice) {
        mongoTemplate.save(invoice, COLLECTION)
    }

    /** How many invoices for this customer, project, warehouse and month were discarded. */
    fun countFor(customerId: Long, projectCode: String?, warehouseId: String?, billingMonth: String): Long =
        mongoTemplate.count(
            Query(
                Criteria.where("customerId").`is`(customerId)
                    .and("projectCode").`is`(projectCode)
                    .and("warehouseId").`is`(warehouseId)
                    .and("billingMonth").`is`(billingMonth)
            ),
            COLLECTION
        )

    companion object {
        const val COLLECTION = "wms_billing_invoice_discarded"
    }
}
