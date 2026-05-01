package com.wmspro.tenant.billing.servicelog

import com.wmspro.tenant.billing.profile.CustomerBillingProfile
import com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * CRUD for [ServiceLog] with two referential validations on every write:
 *   - `customerId` has an active CustomerBillingProfile
 *   - `serviceCode` is in that profile's active serviceSubscriptions[]
 *
 * Business rules:
 *   - `performedAt` cannot be in the future
 *   - Mutations refused on logs locked by a SUBMITTED billing run
 *   - GRN/GIN existence is NOT cross-checked here — the frontend ensures
 *     only valid records can be picked. Adding a Feign hop on every write
 *     would have widened blast radius without meaningful safety gain;
 *     a stale reference is at worst a billing-time data-quality warning.
 *
 * `carriedOverFromMonth` (auto-set when `performedAt` lands in a month
 * already covered by a SUBMITTED billing invoice) is wired in Phase 5
 * once `WmsBillingInvoiceRepository` exists. Field is present on the
 * model now so logs can carry it forward without a migration later.
 */
@Service
class ServiceLogService(
    private val repository: ServiceLogRepository,
    private val billingProfileRepository: CustomerBillingProfileRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findById(serviceLogId: String): ServiceLog? =
        repository.findById(serviceLogId).orElse(null)

    fun findForGrnOrGin(attachedToId: String): List<ServiceLog> =
        repository.findByAttachedToId(attachedToId).sortedByDescending { it.performedAt }

    fun findForCustomer(customerId: Long, from: LocalDate, to: LocalDate): List<ServiceLog> =
        repository.findByCustomerIdAndPerformedAtBetween(customerId, from, to)
            .sortedByDescending { it.performedAt }

    @Transactional
    fun create(request: CreateServiceLogRequest, userEmail: String): ServiceLog {
        validatePerformedAt(request.performedAt)
        val profile = requireProfileWithActiveService(request.customerId, request.serviceCode)

        val now = Instant.now()
        val entity = ServiceLog(
            serviceLogId = "svclog_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            customerId = request.customerId,
            serviceCode = request.serviceCode,
            quantity = request.quantity,
            performedAt = request.performedAt,
            attachedTo = AttachedRef(
                type = request.attachedTo.type,
                id = request.attachedTo.id,
                number = request.attachedTo.number
            ),
            performedBy = userEmail,
            loggedAt = now,
            notes = request.notes,
            billingInvoiceId = null,
            carriedOverFromMonth = null,  // populated by billing engine in Phase 5
            updatedAt = now,
            updatedBy = userEmail
        )
        val saved = repository.save(entity)
        logger.info(
            "ServiceLog created: id={} customerId={} serviceCode={} attachedTo={}/{} qty={} by={}",
            saved.serviceLogId, saved.customerId, saved.serviceCode,
            saved.attachedTo.type, saved.attachedTo.id, saved.quantity, userEmail
        )
        return saved
    }

    @Transactional
    fun update(serviceLogId: String, request: UpdateServiceLogRequest, userEmail: String): ServiceLog {
        val existing = repository.findById(serviceLogId).orElseThrow {
            IllegalArgumentException("ServiceLog '$serviceLogId' not found")
        }
        if (existing.billingInvoiceId != null) {
            throw IllegalStateException(
                "ServiceLog '$serviceLogId' is locked to billing invoice '${existing.billingInvoiceId}'. Cancel the run first to edit."
            )
        }
        validatePerformedAt(request.performedAt)
        // Re-validate even if serviceCode hasn't changed — catalog or
        // subscription state might have moved underneath us.
        requireProfileWithActiveService(existing.customerId, request.serviceCode)

        val updated = existing.copy(
            serviceCode = request.serviceCode,
            quantity = request.quantity,
            performedAt = request.performedAt,
            notes = request.notes,
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun delete(serviceLogId: String, userEmail: String) {
        val existing = repository.findById(serviceLogId).orElseThrow {
            IllegalArgumentException("ServiceLog '$serviceLogId' not found")
        }
        if (existing.billingInvoiceId != null) {
            throw IllegalStateException(
                "ServiceLog '$serviceLogId' is locked to billing invoice '${existing.billingInvoiceId}'. Cancel the run first to delete."
            )
        }
        repository.deleteById(serviceLogId)
        logger.info("ServiceLog deleted: id={} by={}", serviceLogId, userEmail)
    }

    private fun validatePerformedAt(performedAt: LocalDate) {
        if (performedAt.isAfter(LocalDate.now())) {
            throw IllegalArgumentException("performedAt cannot be in the future")
        }
    }

    private fun requireProfileWithActiveService(customerId: Long, serviceCode: String): CustomerBillingProfile {
        val profile = billingProfileRepository.findById(customerId).orElseThrow {
            IllegalArgumentException(
                "Customer $customerId has no billing profile yet. Configure rates and subscriptions first " +
                "(Customer Detail → Billing tab)."
            )
        }
        val sub = profile.serviceSubscriptions.firstOrNull { it.serviceCode == serviceCode }
            ?: throw IllegalArgumentException(
                "Customer $customerId is not subscribed to service '$serviceCode'. Add the subscription on the Billing tab first."
            )
        if (!sub.isActive) {
            throw IllegalStateException(
                "Service '$serviceCode' subscription is inactive for customer $customerId."
            )
        }
        return profile
    }
}
