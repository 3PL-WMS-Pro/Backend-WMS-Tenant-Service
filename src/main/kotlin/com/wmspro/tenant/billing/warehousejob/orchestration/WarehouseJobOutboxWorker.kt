package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

interface WarehouseJobOutboxCommandHandler {
    fun supports(operation: WarehouseJobOutboxOperation): Boolean
    fun handle(command: WarehouseJobOutbox, authToken: String): OutboxCommandResult
}

sealed class OutboxCommandResult {
    data class Success(val remoteEntityId: String, val remoteResponseHash: String) : OutboxCommandResult() {
        init {
            require(remoteEntityId.isNotBlank()) { "A successful outbox command requires the remote identity" }
            require(remoteResponseHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "A successful outbox command requires a SHA-256 remote response fingerprint"
            }
        }
    }
    data class Retry(val sanitizedError: String) : OutboxCommandResult()
    data class ManualReview(val sanitizedError: String) : OutboxCommandResult()
}

@Service
class WarehouseJobOutboxWorker(
    private val mongoTemplate: MongoTemplate,
    private val outboxRepository: WarehouseJobOutboxRepository,
    private val claimIntentRepository: WarehouseJobClaimIntentRepository,
    private val bindingService: WarehouseJobBindingService,
    private val handlers: List<WarehouseJobOutboxCommandHandler>
) {
    fun drainCurrentTenant(workerId: String, authToken: String, limit: Int = 25): Int =
        drain(workerId, authToken, limit, null)

    fun drainInvoice(workerId: String, billingInvoiceId: String, authToken: String, limit: Int = 2): Int =
        drain(workerId, authToken, limit, billingInvoiceId)

    private fun drain(workerId: String, authToken: String, limit: Int, billingInvoiceId: String?): Int {
        check(!TenantContext.getCurrentTenant().isNullOrBlank() && MongoConnectionStorage.hasExplicitConnection()) {
            "Warehouse Job worker requires explicit tenant and Mongo connection contexts"
        }
        var processed = 0
        repeat(limit.coerceIn(1, 100)) {
            val command = leaseNext(workerId, billingInvoiceId) ?: return processed
            val intent = claimIntentRepository.findById(command.billingInvoiceId).orElse(null)
            val dependencyReady = command.dependencyOutboxId == null ||
                outboxRepository.findById(command.dependencyOutboxId).orElse(null)?.let {
                    it.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                        it.state == WarehouseJobOutboxState.SUCCEEDED
                } == true
            val handler = handlers.firstOrNull { it.supports(command.operation) }
            val result = if (intent?.state == WarehouseJobClaimIntentState.MANUAL_REVIEW) {
                OutboxCommandResult.ManualReview(intent.lastError ?: "Source claims require manual review")
            } else if (intent?.state != WarehouseJobClaimIntentState.READY) {
                OutboxCommandResult.Retry("Waiting for every source claim to be committed")
            } else if (!dependencyReady) {
                OutboxCommandResult.Retry("Waiting for predecessor command")
            } else {
                handler?.handle(command, authToken)
                    ?: OutboxCommandResult.ManualReview("No Warehouse Job outbox handler is installed")
            }
            complete(command, workerId, result)
            processed++
        }
        return processed
    }

    private fun leaseNext(workerId: String, billingInvoiceId: String?): WarehouseJobOutbox? {
        val now = Instant.now()
        val due = Criteria().andOperator(
            Criteria.where("state").`in`(WarehouseJobOutboxState.PENDING, WarehouseJobOutboxState.RETRY_WAIT),
            Criteria.where("nextAttemptAt").lte(now)
        )
        val crashedLease = Criteria().andOperator(
            Criteria.where("state").`is`(WarehouseJobOutboxState.LEASED),
            Criteria.where("leaseUntil").lt(now)
        )
        val criteria = mutableListOf(
            Criteria.where("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1),
            Criteria().orOperator(due, crashedLease)
        )
        billingInvoiceId?.let { criteria += Criteria.where("billingInvoiceId").`is`(it) }
        val query = Query.query(Criteria().andOperator(*criteria.toTypedArray()))
            .with(org.springframework.data.domain.Sort.by("createdAt").ascending())
        val update = Update()
            .set("state", WarehouseJobOutboxState.LEASED)
            .set("leaseOwner", workerId)
            .set("leaseUntil", now.plus(Duration.ofMinutes(2)))
            .inc("leaseToken", 1)
            .set("updatedAt", now)
        return mongoTemplate.findAndModify(
            query,
            update,
            FindAndModifyOptions.options().returnNew(true),
            WarehouseJobOutbox::class.java
        )
    }

    private fun complete(command: WarehouseJobOutbox, workerId: String, result: OutboxCommandResult) {
        val query = Query.query(
            Criteria.where("outboxId").`is`(command.outboxId)
                .and("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
                .and("state").`is`(WarehouseJobOutboxState.LEASED)
                .and("leaseOwner").`is`(workerId)
                .and("leaseToken").`is`(command.leaseToken)
        )
        val now = Instant.now()
        val update = Update().unset("leaseOwner").unset("leaseUntil").set("updatedAt", now)
        when (result) {
            is OutboxCommandResult.Success -> update
                .set("state", WarehouseJobOutboxState.SUCCEEDED)
                .set("remoteEntityId", result.remoteEntityId)
                .set("remoteResponseHash", result.remoteResponseHash)
                .set("completedAt", now)
                .unset("lastError")
            is OutboxCommandResult.Retry -> if (command.attempts + 1 >= MAX_ATTEMPTS) {
                bindingService.failure(command, "Retry limit reached: ${result.sanitizedError}", true)
                update.set("state", WarehouseJobOutboxState.MANUAL_REVIEW)
                    .set("lastError", "Retry limit reached: ${result.sanitizedError}".take(500))
                    .inc("attempts", 1)
            }
            else update.set("state", WarehouseJobOutboxState.RETRY_WAIT)
                .set("lastError", result.sanitizedError.take(500))
                .set("nextAttemptAt", now.plusSeconds(backoffSeconds(command.attempts + 1)))
                .inc("attempts", 1)
            is OutboxCommandResult.ManualReview -> update
                .set("state", WarehouseJobOutboxState.MANUAL_REVIEW)
                .set("lastError", result.sanitizedError.take(500))
                .inc("attempts", 1)
        }
        mongoTemplate.updateFirst(query, update, WarehouseJobOutbox::class.java)
    }

    private fun backoffSeconds(attempt: Int): Long = (15L shl attempt.coerceAtMost(8)).coerceAtMost(3600L)

    private companion object { const val MAX_ATTEMPTS = 12 }
}
