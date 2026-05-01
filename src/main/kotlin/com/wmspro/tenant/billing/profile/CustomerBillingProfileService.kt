package com.wmspro.tenant.billing.profile

import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.tenant.billing.catalog.ServiceCatalogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * CRUD + granular-mutation service for [CustomerBillingProfile].
 *
 * Cross-validates two referential boundaries on every write:
 *   - All three `freighai*ChargeTypeId`s exist & are active in FreighAi
 *     (via [FreighAiChargeTypeClient]). Caller must pass the inbound
 *     `Authorization` token so this can be done server-to-server.
 *   - All `serviceSubscriptions[].serviceCode`s exist & are active in the
 *     local [com.wmspro.tenant.billing.catalog.ServiceCatalog].
 *
 * Project / subscription uniqueness within a profile is enforced (no two
 * entries with the same projectCode / serviceCode). Soft-delete only — flip
 * `isActive=false` rather than dropping rows so historical billing audit
 * trails stay intact.
 */
@Service
class CustomerBillingProfileService(
    private val repository: CustomerBillingProfileRepository,
    private val freighAiChargeTypeClient: FreighAiChargeTypeClient,
    private val serviceCatalogRepository: ServiceCatalogRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findByCustomerId(customerId: Long): CustomerBillingProfile? =
        repository.findById(customerId).orElse(null)

    // ──────────────────────────────────────────────────────────────────────
    // Full upsert
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    fun upsert(
        customerId: Long,
        request: UpsertCustomerBillingProfileRequest,
        userEmail: String,
        authToken: String
    ): CustomerBillingProfile {
        validateChargeTypes(
            storageChargeTypeId = request.freighaiStorageChargeTypeId,
            inboundChargeTypeId = request.freighaiInboundMovementChargeTypeId,
            outboundChargeTypeId = request.freighaiOutboundMovementChargeTypeId,
            authToken = authToken
        )
        validateProjectUniqueness(request.projects.map { it.projectCode })
        validateSubscriptions(request.serviceSubscriptions.map { it.serviceCode })

        val now = Instant.now()
        val existing = repository.findById(customerId).orElse(null)

        val entity = if (existing == null) {
            CustomerBillingProfile(
                customerId = customerId,
                defaultCbmRatePerDay = request.defaultCbmRatePerDay,
                defaultInboundCbmRate = request.defaultInboundCbmRate,
                defaultOutboundCbmRate = request.defaultOutboundCbmRate,
                defaultMonthlyMinimum = request.defaultMonthlyMinimum,
                projects = request.projects.map { it.toModel() },
                serviceSubscriptions = request.serviceSubscriptions.map { it.toModel() },
                freighaiStorageChargeTypeId = request.freighaiStorageChargeTypeId,
                freighaiInboundMovementChargeTypeId = request.freighaiInboundMovementChargeTypeId,
                freighaiOutboundMovementChargeTypeId = request.freighaiOutboundMovementChargeTypeId,
                billingEnabled = request.billingEnabled,
                createdAt = now,
                updatedAt = now,
                createdBy = userEmail,
                updatedBy = userEmail
            )
        } else {
            existing.copy(
                defaultCbmRatePerDay = request.defaultCbmRatePerDay,
                defaultInboundCbmRate = request.defaultInboundCbmRate,
                defaultOutboundCbmRate = request.defaultOutboundCbmRate,
                defaultMonthlyMinimum = request.defaultMonthlyMinimum,
                projects = request.projects.map { it.toModel() },
                serviceSubscriptions = request.serviceSubscriptions.map { it.toModel() },
                freighaiStorageChargeTypeId = request.freighaiStorageChargeTypeId,
                freighaiInboundMovementChargeTypeId = request.freighaiInboundMovementChargeTypeId,
                freighaiOutboundMovementChargeTypeId = request.freighaiOutboundMovementChargeTypeId,
                billingEnabled = request.billingEnabled,
                updatedAt = now,
                updatedBy = userEmail
            )
        }
        val saved = repository.save(entity)
        logger.info(
            "CustomerBillingProfile {}: customerId={}, projects={}, services={}, billingEnabled={}, by={}",
            if (existing == null) "created" else "updated",
            saved.customerId,
            saved.projects.size,
            saved.serviceSubscriptions.size,
            saved.billingEnabled,
            userEmail
        )
        return saved
    }

    // ──────────────────────────────────────────────────────────────────────
    // Granular project mutations
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    fun addProject(
        customerId: Long,
        request: AddProjectRateRequest,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        if (profile.projects.any { it.projectCode == request.projectCode }) {
            throw IllegalStateException(
                "Project '${request.projectCode}' already exists on this profile. Use PUT to update or set isActive=true to revive."
            )
        }
        val updated = profile.copy(
            projects = profile.projects + ProjectRate(
                projectCode = request.projectCode,
                label = request.label,
                cbmRatePerDay = request.cbmRatePerDay,
                inboundCbmRate = request.inboundCbmRate,
                outboundCbmRate = request.outboundCbmRate,
                isActive = request.isActive
            ),
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun updateProject(
        customerId: Long,
        projectCode: String,
        request: UpdateProjectRateRequest,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        if (profile.projects.none { it.projectCode == projectCode }) {
            throw IllegalArgumentException("Project '$projectCode' not found on profile customerId=$customerId")
        }
        val updated = profile.copy(
            projects = profile.projects.map { p ->
                if (p.projectCode == projectCode) {
                    p.copy(
                        label = request.label,
                        cbmRatePerDay = request.cbmRatePerDay,
                        inboundCbmRate = request.inboundCbmRate,
                        outboundCbmRate = request.outboundCbmRate,
                        isActive = request.isActive
                    )
                } else p
            },
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun deactivateProject(
        customerId: Long,
        projectCode: String,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        val match = profile.projects.firstOrNull { it.projectCode == projectCode }
            ?: throw IllegalArgumentException("Project '$projectCode' not found on profile customerId=$customerId")
        if (!match.isActive) return profile  // idempotent
        val updated = profile.copy(
            projects = profile.projects.map { p ->
                if (p.projectCode == projectCode) p.copy(isActive = false) else p
            },
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Granular subscription mutations
    // ──────────────────────────────────────────────────────────────────────

    @Transactional
    fun addServiceSubscription(
        customerId: Long,
        request: AddServiceSubscriptionRequest,
        userEmail: String
    ): CustomerBillingProfile {
        validateSubscriptions(listOf(request.serviceCode))
        val profile = requireProfile(customerId)
        if (profile.serviceSubscriptions.any { it.serviceCode == request.serviceCode }) {
            throw IllegalStateException(
                "Service '${request.serviceCode}' already subscribed. Use PUT to update."
            )
        }
        val updated = profile.copy(
            serviceSubscriptions = profile.serviceSubscriptions + ServiceSubscription(
                serviceCode = request.serviceCode,
                customRatePerUnit = request.customRatePerUnit,
                isActive = request.isActive
            ),
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun updateServiceSubscription(
        customerId: Long,
        serviceCode: String,
        request: UpdateServiceSubscriptionRequest,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        if (profile.serviceSubscriptions.none { it.serviceCode == serviceCode }) {
            throw IllegalArgumentException("Service subscription '$serviceCode' not found on profile customerId=$customerId")
        }
        val updated = profile.copy(
            serviceSubscriptions = profile.serviceSubscriptions.map { s ->
                if (s.serviceCode == serviceCode) {
                    s.copy(
                        customRatePerUnit = request.customRatePerUnit,
                        isActive = request.isActive
                    )
                } else s
            },
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun deactivateServiceSubscription(
        customerId: Long,
        serviceCode: String,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        val match = profile.serviceSubscriptions.firstOrNull { it.serviceCode == serviceCode }
            ?: throw IllegalArgumentException("Service subscription '$serviceCode' not found on profile customerId=$customerId")
        if (!match.isActive) return profile
        val updated = profile.copy(
            serviceSubscriptions = profile.serviceSubscriptions.map { s ->
                if (s.serviceCode == serviceCode) s.copy(isActive = false) else s
            },
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        return repository.save(updated)
    }

    @Transactional
    fun setBillingEnabled(
        customerId: Long,
        enabled: Boolean,
        userEmail: String
    ): CustomerBillingProfile {
        val profile = requireProfile(customerId)
        if (profile.billingEnabled == enabled) return profile
        return repository.save(
            profile.copy(
                billingEnabled = enabled,
                updatedAt = Instant.now(),
                updatedBy = userEmail
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun requireProfile(customerId: Long): CustomerBillingProfile =
        repository.findById(customerId).orElseThrow {
            IllegalArgumentException("No billing profile for customerId=$customerId. Create one first via PUT /api/v1/billing-profiles/$customerId")
        }

    /**
     * Verifies all three FreighAi ChargeType IDs exist & are active. We hit
     * FreighAi sequentially (only 3 calls — no need to parallelise) and
     * collect failures so the caller sees all problems at once instead of
     * fixing them one-by-one.
     */
    private fun validateChargeTypes(
        storageChargeTypeId: String,
        inboundChargeTypeId: String,
        outboundChargeTypeId: String,
        authToken: String
    ) {
        val errors = mutableListOf<String>()
        listOf(
            "storage" to storageChargeTypeId,
            "inbound movement" to inboundChargeTypeId,
            "outbound movement" to outboundChargeTypeId
        ).forEach { (kind, id) ->
            val resolved = freighAiChargeTypeClient.getChargeType(id, authToken)
            when {
                resolved == null -> errors += "FreighAi ChargeType '$id' (for $kind) not found in FreighAi"
                !resolved.isActive -> errors += "FreighAi ChargeType '$id' (${resolved.label}) for $kind is inactive in FreighAi"
            }
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }
    }

    private fun validateProjectUniqueness(projectCodes: List<String>) {
        val duplicates = projectCodes.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Duplicate projectCode(s) in request: ${duplicates.joinToString(", ")}")
        }
    }

    /**
     * Validate that every referenced serviceCode exists in the local catalog.
     * Inactive catalog entries are blocked too — admin should reactivate the
     * catalog entry before subscribing customers to it. This catches typos
     * and stale references at write time rather than at billing time.
     */
    private fun validateSubscriptions(serviceCodes: List<String>) {
        if (serviceCodes.isEmpty()) return
        val duplicates = serviceCodes.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw IllegalArgumentException("Duplicate serviceCode(s) in request: ${duplicates.joinToString(", ")}")
        }
        val unique = serviceCodes.toSet()
        val foundEntries = serviceCatalogRepository.findAllById(unique).associateBy { it.serviceCode }
        val errors = mutableListOf<String>()
        unique.forEach { code ->
            val entry = foundEntries[code]
            when {
                entry == null -> errors += "ServiceCatalog entry '$code' not found. Create it in Settings → Billing → Service Catalog first."
                !entry.isActive -> errors += "ServiceCatalog entry '$code' is inactive. Reactivate it before subscribing customers."
            }
        }
        if (errors.isNotEmpty()) {
            throw IllegalArgumentException(errors.joinToString("; "))
        }
    }

    private fun ProjectRateInput.toModel() = ProjectRate(
        projectCode = projectCode,
        label = label,
        cbmRatePerDay = cbmRatePerDay,
        inboundCbmRate = inboundCbmRate,
        outboundCbmRate = outboundCbmRate,
        isActive = isActive
    )

    private fun ServiceSubscriptionInput.toModel() = ServiceSubscription(
        serviceCode = serviceCode,
        customRatePerUnit = customRatePerUnit,
        isActive = isActive
    )
}
