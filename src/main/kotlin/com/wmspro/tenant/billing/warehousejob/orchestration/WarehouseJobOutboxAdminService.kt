package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WarehouseJobOutboxAdminService(private val mongoTemplate: MongoTemplate) {
    fun quarantine(billingInvoiceId: String, error: String) {
        mongoTemplate.updateMulti(
            scoped(billingInvoiceId).addCriteria(Criteria.where("state").ne(WarehouseJobOutboxState.SUCCEEDED)),
            Update().set("state", WarehouseJobOutboxState.MANUAL_REVIEW)
                .set("lastError", error.take(500)).unset("leaseOwner").unset("leaseUntil")
                .set("updatedAt", Instant.now()),
            WarehouseJobOutbox::class.java
        )
    }

    fun retry(billingInvoiceId: String): Long {
        val result = mongoTemplate.updateMulti(
            scoped(billingInvoiceId).addCriteria(
                Criteria.where("state").`in`(WarehouseJobOutboxState.RETRY_WAIT, WarehouseJobOutboxState.MANUAL_REVIEW)
            ),
            Update().set("state", WarehouseJobOutboxState.PENDING).set("nextAttemptAt", Instant.now())
                .unset("leaseOwner").unset("leaseUntil").unset("lastError").set("updatedAt", Instant.now()),
            WarehouseJobOutbox::class.java
        )
        return result.modifiedCount
    }

    fun cancelUndelivered(billingInvoiceId: String) {
        mongoTemplate.updateMulti(
            scoped(billingInvoiceId).addCriteria(Criteria.where("state").ne(WarehouseJobOutboxState.SUCCEEDED)),
            Update().set("state", WarehouseJobOutboxState.CANCELLED).unset("leaseOwner").unset("leaseUntil")
                .unset("lastError").set("updatedAt", Instant.now()),
            WarehouseJobOutbox::class.java
        )
    }

    private fun scoped(id: String) = Query.query(
        Criteria.where("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
            .and("billingInvoiceId").`is`(id)
    )
}
