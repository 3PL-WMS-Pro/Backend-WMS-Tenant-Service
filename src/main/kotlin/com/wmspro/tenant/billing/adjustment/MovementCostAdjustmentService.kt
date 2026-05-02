package com.wmspro.tenant.billing.adjustment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * CRUD for [MovementCostAdjustment].
 *
 * Lock semantics (mirror of ServiceLogService):
 *   - Mutation refused (409) when `billingInvoiceId` is set.
 *   - billingInvoiceId is set/cleared by [com.wmspro.tenant.billing.invoice.BillingRunService]
 *     during billing-run generation/cancellation.
 */
@Service
class MovementCostAdjustmentService(
    private val repository: MovementCostAdjustmentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findById(adjustmentId: String): MovementCostAdjustment? =
        repository.findById(adjustmentId).orElse(null)

    fun findForRecord(attachedToId: String): List<MovementCostAdjustment> =
        repository.findByAttachedToId(attachedToId).sortedByDescending { it.createdAt }

    fun findUnbilledForRecord(attachedToId: String): List<MovementCostAdjustment> =
        repository.findByAttachedToIdAndBillingInvoiceIdIsNull(attachedToId)

    fun findForCustomer(customerId: Long, from: Instant, to: Instant): List<MovementCostAdjustment> =
        repository.findByCustomerIdAndCreatedAtBetween(customerId, from, to)
            .sortedByDescending { it.createdAt }

    @Transactional
    fun create(request: CreateMovementCostAdjustmentRequest, userEmail: String): MovementCostAdjustment {
        val now = Instant.now()
        val entity = MovementCostAdjustment(
            adjustmentId = "mvadj_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            customerId = request.customerId,
            attachedTo = AdjustmentAttachedRef(
                type = request.attachedTo.type,
                id = request.attachedTo.id,
                number = request.attachedTo.number
            ),
            direction = request.direction,
            reason = request.reason,
            ratePerUnitDelta = request.ratePerUnitDelta,
            notes = request.notes,
            createdBy = userEmail,
            createdAt = now,
            updatedAt = now,
            updatedBy = userEmail
        )
        val saved = repository.save(entity)
        logger.info(
            "MovementCostAdjustment created: id={} customerId={} attachedTo={}/{} delta={} by={}",
            saved.adjustmentId, saved.customerId,
            saved.attachedTo.type, saved.attachedTo.id, saved.ratePerUnitDelta, userEmail
        )
        return saved
    }

    @Transactional
    fun update(adjustmentId: String, request: UpdateMovementCostAdjustmentRequest, userEmail: String): MovementCostAdjustment {
        val existing = repository.findById(adjustmentId).orElseThrow {
            IllegalArgumentException("MovementCostAdjustment '$adjustmentId' not found")
        }
        if (existing.billingInvoiceId != null) {
            throw IllegalStateException(
                "MovementCostAdjustment '$adjustmentId' is locked to billing invoice " +
                "'${existing.billingInvoiceId}'. Cancel the run first to edit."
            )
        }
        val updated = existing.copy(
            reason = request.reason,
            ratePerUnitDelta = request.ratePerUnitDelta,
            notes = request.notes,
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun delete(adjustmentId: String, userEmail: String) {
        val existing = repository.findById(adjustmentId).orElseThrow {
            IllegalArgumentException("MovementCostAdjustment '$adjustmentId' not found")
        }
        if (existing.billingInvoiceId != null) {
            throw IllegalStateException(
                "MovementCostAdjustment '$adjustmentId' is locked to billing invoice " +
                "'${existing.billingInvoiceId}'. Cancel the run first to delete."
            )
        }
        repository.deleteById(adjustmentId)
        logger.info("MovementCostAdjustment deleted: id={} by={}", adjustmentId, userEmail)
    }

    /**
     * Called by BillingRunService.generate() when a snapshot includes this
     * adjustment. Sets billingInvoiceId so further edits/deletes throw 409.
     */
    @Transactional
    fun lockToBillingInvoice(adjustmentIds: Collection<String>, billingInvoiceId: String) {
        if (adjustmentIds.isEmpty()) return
        val now = Instant.now()
        val updated = repository.findAllById(adjustmentIds).map {
            it.copy(billingInvoiceId = billingInvoiceId, updatedAt = now)
        }
        repository.saveAll(updated)
    }

    /**
     * Called by BillingRunService.cancel() to release the lock so admins
     * can edit/delete the adjustment again.
     */
    @Transactional
    fun unlockFromBillingInvoice(billingInvoiceId: String) {
        val toUnlock = repository.findByBillingInvoiceId(billingInvoiceId)
        if (toUnlock.isEmpty()) return
        val now = Instant.now()
        val updated = toUnlock.map { it.copy(billingInvoiceId = null, updatedAt = now) }
        repository.saveAll(updated)
    }
}
