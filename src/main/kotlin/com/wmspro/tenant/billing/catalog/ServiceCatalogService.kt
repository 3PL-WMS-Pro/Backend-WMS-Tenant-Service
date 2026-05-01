package com.wmspro.tenant.billing.catalog

import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Service for managing the per-tenant ServiceCatalog.
 *
 * Validates that `freighaiChargeTypeId` actually exists in FreighAi at write
 * time so the catalog can never bind to a deleted/typo'd ChargeType. The
 * FreighAi auth token must be passed in by the controller (extracted from the
 * inbound `Authorization` header) — the same pattern used by
 * [com.wmspro.tenant.service.CustomerMasterProxyService] and friends.
 *
 * `IllegalArgumentException` → 400 in the controller. `IllegalStateException`
 * → 409. Matches the convention used in `DocumentTemplateService` so the
 * controller's catch blocks stay consistent across the codebase.
 */
@Service
class ServiceCatalogService(
    private val repository: ServiceCatalogRepository,
    private val freighAiChargeTypeClient: FreighAiChargeTypeClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun listAll(includeInactive: Boolean): List<ServiceCatalog> =
        if (includeInactive) repository.findAll().toList()
        else repository.findByIsActive(true)

    fun findByCode(serviceCode: String): ServiceCatalog? =
        repository.findById(serviceCode).orElse(null)

    @Transactional
    fun create(request: CreateServiceCatalogRequest, userEmail: String, authToken: String): ServiceCatalog {
        if (repository.existsById(request.serviceCode)) {
            throw IllegalStateException("Service code '${request.serviceCode}' already exists")
        }
        validateFreighAiChargeType(request.freighaiChargeTypeId, authToken)

        val now = Instant.now()
        val entity = ServiceCatalog(
            serviceCode = request.serviceCode,
            label = request.label,
            unit = request.unit,
            standardRatePerUnit = request.standardRatePerUnit,
            freighaiChargeTypeId = request.freighaiChargeTypeId,
            vatPercent = request.vatPercent,
            isActive = request.isActive,
            createdAt = now,
            updatedAt = now,
            createdBy = userEmail,
            updatedBy = userEmail
        )
        val saved = repository.save(entity)
        logger.info("ServiceCatalog created: code={}, by={}", saved.serviceCode, userEmail)
        return saved
    }

    @Transactional
    fun update(serviceCode: String, request: UpdateServiceCatalogRequest, userEmail: String, authToken: String): ServiceCatalog {
        val existing = repository.findById(serviceCode).orElseThrow {
            IllegalArgumentException("Service code '$serviceCode' not found")
        }
        // Only re-validate the FreighAi ChargeType when the caller is actually
        // changing the binding — saves a network hop on every label/rate edit.
        if (existing.freighaiChargeTypeId != request.freighaiChargeTypeId) {
            validateFreighAiChargeType(request.freighaiChargeTypeId, authToken)
        }

        val updated = existing.copy(
            label = request.label,
            unit = request.unit,
            standardRatePerUnit = request.standardRatePerUnit,
            freighaiChargeTypeId = request.freighaiChargeTypeId,
            vatPercent = request.vatPercent,
            isActive = request.isActive,
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        val saved = repository.save(updated)
        logger.info("ServiceCatalog updated: code={}, by={}", saved.serviceCode, userEmail)
        return saved
    }

    /**
     * Soft-delete: flip `isActive=false`. Hard-delete is not exposed because
     * historical ServiceLog rows reference `serviceCode`; removing the catalog
     * entry would orphan them. Future hard-delete (when no logs reference the
     * code) would be safe but isn't worth the complexity yet.
     */
    @Transactional
    fun deactivate(serviceCode: String, userEmail: String): ServiceCatalog {
        val existing = repository.findById(serviceCode).orElseThrow {
            IllegalArgumentException("Service code '$serviceCode' not found")
        }
        if (!existing.isActive) {
            return existing  // idempotent
        }
        val updated = existing.copy(
            isActive = false,
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        val saved = repository.save(updated)
        logger.info("ServiceCatalog deactivated: code={}, by={}", saved.serviceCode, userEmail)
        return saved
    }

    private fun validateFreighAiChargeType(chargeTypeId: String, authToken: String) {
        val resolved = freighAiChargeTypeClient.getChargeType(chargeTypeId, authToken)
            ?: throw IllegalArgumentException(
                "FreighAi ChargeType '$chargeTypeId' not found. Pick an existing entry from the dropdown or ask the customer to add it in FreighAi first."
            )
        if (!resolved.isActive) {
            throw IllegalStateException(
                "FreighAi ChargeType '$chargeTypeId' (${resolved.label}) is inactive in FreighAi. Reactivate it there or pick a different one."
            )
        }
    }
}
