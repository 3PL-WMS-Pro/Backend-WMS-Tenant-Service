package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Service
import java.time.Instant

enum class WarehouseJobClaimIntentState {
    /** Reservations may be acquired, but no remote writer may run. */
    CLAIMING,
    /** The local invoice/snapshot/outbox transaction committed; claim commits are still being confirmed. */
    FROZEN,
    /** Every source claim is confirmed COMMITTED and remote delivery may start. */
    READY,
    RELEASED,
    MANUAL_REVIEW
}

/**
 * Durable authorization for the pre-freeze reservation phase.  It is a new
 * V1-only collection, never an annotation or rewrite of a historical invoice.
 */
@Document(collection = "warehouse_job_claim_intent")
@CompoundIndexes(
    CompoundIndex(
        name = "wj_claim_intent_tuple_uq",
        def = "{'generationContractVersion':1,'customerId':1,'projectBucket':1,'billingMonth':1}",
        unique = true
    )
)
data class WarehouseJobClaimIntent(
    @Id val billingInvoiceId: String,
    val generationContractVersion: String = WarehouseJobGenerationContracts.V1,
    val payloadVersion: Long,
    val customerId: Long,
    val projectBucket: String,
    val billingMonth: String,
    val sourceFingerprint: String,
    val state: WarehouseJobClaimIntentState = WarehouseJobClaimIntentState.CLAIMING,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastError: String? = null
)

interface WarehouseJobClaimIntentRepository : MongoRepository<WarehouseJobClaimIntent, String>

class WarehouseJobClaimIntentConflict(message: String) : IllegalStateException(message)

@Service
class WarehouseJobClaimIntentService(private val repository: WarehouseJobClaimIntentRepository) {
    fun createOrAdopt(intent: WarehouseJobClaimIntent): WarehouseJobClaimIntent {
        require(intent.generationContractVersion == WarehouseJobGenerationContracts.V1)
        val existing = repository.findById(intent.billingInvoiceId).orElse(null)
        if (existing != null) return adoptExact(existing, intent)
        return try {
            repository.insert(intent)
        } catch (_: DuplicateKeyException) {
            val winner = repository.findById(intent.billingInvoiceId).orElse(null)
                ?: throw WarehouseJobClaimIntentConflict("CUTOVER_CONFLICT: billing tuple is owned by another V1 intent")
            adoptExact(winner, intent)
        }
    }

    fun markFrozen(billingInvoiceId: String) = transition(billingInvoiceId, WarehouseJobClaimIntentState.FROZEN, null)
    fun markReady(billingInvoiceId: String) = transition(billingInvoiceId, WarehouseJobClaimIntentState.READY, null)
    fun markReleased(billingInvoiceId: String, error: String?) =
        transition(billingInvoiceId, WarehouseJobClaimIntentState.RELEASED, error)
    fun markManualReview(billingInvoiceId: String, error: String) =
        transition(billingInvoiceId, WarehouseJobClaimIntentState.MANUAL_REVIEW, error)

    private fun transition(id: String, state: WarehouseJobClaimIntentState, error: String?): WarehouseJobClaimIntent {
        val current = repository.findById(id).orElseThrow { WarehouseJobClaimIntentConflict("Claim intent '$id' not found") }
        if (current.state == state) return current
        val allowed = when (current.state) {
            WarehouseJobClaimIntentState.CLAIMING -> setOf(
                WarehouseJobClaimIntentState.FROZEN,
                WarehouseJobClaimIntentState.RELEASED,
                WarehouseJobClaimIntentState.MANUAL_REVIEW
            )
            WarehouseJobClaimIntentState.FROZEN -> setOf(
                WarehouseJobClaimIntentState.READY,
                WarehouseJobClaimIntentState.MANUAL_REVIEW
            )
            WarehouseJobClaimIntentState.MANUAL_REVIEW -> setOf(WarehouseJobClaimIntentState.READY)
            WarehouseJobClaimIntentState.READY,
            WarehouseJobClaimIntentState.RELEASED -> emptySet()
        }
        if (state !in allowed) {
            throw WarehouseJobClaimIntentConflict("CUTOVER_CONFLICT: illegal claim intent transition ${current.state} -> $state")
        }
        return repository.save(current.copy(state = state, updatedAt = Instant.now(), lastError = error?.take(500)))
    }

    private fun adoptExact(existing: WarehouseJobClaimIntent, requested: WarehouseJobClaimIntent): WarehouseJobClaimIntent {
        if (existing.generationContractVersion != requested.generationContractVersion ||
            existing.payloadVersion != requested.payloadVersion ||
            existing.customerId != requested.customerId ||
            existing.projectBucket != requested.projectBucket ||
            existing.billingMonth != requested.billingMonth ||
            existing.sourceFingerprint != requested.sourceFingerprint ||
            existing.state !in setOf(
                WarehouseJobClaimIntentState.CLAIMING,
                WarehouseJobClaimIntentState.FROZEN,
                WarehouseJobClaimIntentState.READY,
                WarehouseJobClaimIntentState.RELEASED
            )
        ) {
            throw WarehouseJobClaimIntentConflict("CUTOVER_CONFLICT: claim intent identity/evidence differs")
        }
        if (existing.state != WarehouseJobClaimIntentState.RELEASED) {
            // An exact identity is not ownership. A second manual/cron node
            // must not share RESERVED claims with the active generator: its
            // rollback could release the winner's claims between freeze and
            // commit. Only a deliberately RELEASED attempt may be restarted.
            throw WarehouseJobClaimIntentConflict(
                "CUTOVER_CONFLICT: V1 generation is already ${existing.state} for this billing tuple"
            )
        }
        return repository.save(
            existing.copy(
                state = WarehouseJobClaimIntentState.CLAIMING,
                expiresAt = requested.expiresAt,
                updatedAt = Instant.now(),
                lastError = null
            )
        )
    }
}
