package com.wmspro.tenant.billing.invoice

import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Moves `wms_billing_invoice` from one-invoice-per-(customer, project, month) to
 * one-per-(customer, project, warehouse, month), in whichever tenant database is current.
 *
 * Spring's auto-index creation adds the new index but never drops the old one, and the old one
 * would reject a customer's second warehouse invoice for the same month. The new index is created
 * before the old one is dropped, so uniqueness is never absent; both are safe over existing data
 * because a missing `warehouseId` indexes as null. Runs once per database per process.
 */
@Component
class BillingInvoiceIndexMigration(
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val migratedDatabases = ConcurrentHashMap.newKeySet<String>()

    fun ensure() {
        val database = mongoTemplate.db.name
        if (database in migratedDatabases) return

        val indexOps = mongoTemplate.indexOps(WmsBillingInvoice::class.java)
        val existing = indexOps.indexInfo.map { it.name }.toSet()
        if (CURRENT !in existing) {
            indexOps.ensureIndex(
                Index()
                    .on("customerId", Sort.Direction.ASC)
                    .on("projectCode", Sort.Direction.ASC)
                    .on("warehouseId", Sort.Direction.ASC)
                    .on("billingMonth", Sort.Direction.ASC)
                    .unique()
                    .named(CURRENT)
            )
            logger.info("Created {} on {}.wms_billing_invoice", CURRENT, database)
        }
        if (SUPERSEDED in existing) {
            indexOps.dropIndex(SUPERSEDED)
            logger.info("Dropped {} on {}.wms_billing_invoice", SUPERSEDED, database)
        }
        migratedDatabases += database
    }

    companion object {
        const val CURRENT = "customer_project_warehouse_month_unique_idx"
        const val SUPERSEDED = "customer_project_month_unique_idx"
    }
}
