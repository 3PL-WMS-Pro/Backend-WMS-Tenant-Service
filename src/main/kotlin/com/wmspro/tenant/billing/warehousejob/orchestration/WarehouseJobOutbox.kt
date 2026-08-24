package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import java.time.Instant

enum class WarehouseJobOutboxOperation { UPSERT_WJ, CREATE_OR_LINK_SI }
enum class WarehouseJobOutboxState { PENDING, LEASED, SUCCEEDED, RETRY_WAIT, MANUAL_REVIEW, CANCELLED }

@Document(collection = "warehouse_job_outbox")
@CompoundIndexes(
    CompoundIndex(name = "wj_outbox_identity_uq", def = "{'generationContractVersion':1,'idempotencyKey':1}", unique = true),
    CompoundIndex(name = "wj_outbox_due_idx", def = "{'generationContractVersion':1,'state':1,'nextAttemptAt':1,'leaseUntil':1}"),
    CompoundIndex(name = "wj_outbox_invoice_idx", def = "{'billingInvoiceId':1,'payloadVersion':1,'operation':1}")
)
data class WarehouseJobOutbox(
    @Id val outboxId: String,
    val generationContractVersion: String = WarehouseJobGenerationContracts.V1,
    @Indexed val billingInvoiceId: String,
    val payloadVersion: Long,
    val operation: WarehouseJobOutboxOperation,
    val idempotencyKey: String,
    val dependencyOutboxId: String? = null,
    /** Canonical compact JSON; payloadHash is calculated before insertion. */
    val payload: String,
    val payloadHash: String,
    val state: WarehouseJobOutboxState = WarehouseJobOutboxState.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Instant = Instant.now(),
    val leaseOwner: String? = null,
    val leaseToken: Long = 0,
    val leaseUntil: Instant? = null,
    val lastError: String? = null,
    val correlationId: String,
    /** Remote identity and deterministic response fingerprint captured only after a fenced success. */
    val remoteEntityId: String? = null,
    val remoteResponseHash: String? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

interface WarehouseJobOutboxRepository : MongoRepository<WarehouseJobOutbox, String> {
    fun existsByGenerationContractVersionAndBillingInvoiceId(
        generationContractVersion: String,
        billingInvoiceId: String
    ): Boolean

    fun findByGenerationContractVersionAndBillingInvoiceId(
        generationContractVersion: String,
        billingInvoiceId: String
    ): List<WarehouseJobOutbox>
}
