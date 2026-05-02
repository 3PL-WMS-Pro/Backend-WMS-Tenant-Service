package com.wmspro.tenant.billing.defaults

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Singleton get/upsert. The cascade-resolution code in BillingRunService
 * uses [findOrNull] for read-side fallback (null = "no tenant defaults
 * configured yet" — the billing run will surface a clear error if it needs
 * a fallback that doesn't exist).
 */
@Service
class TenantBillingDefaultsService(
    private val repository: TenantBillingDefaultsRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findOrNull(): TenantBillingDefaults? =
        repository.findById(TenantBillingDefaults.SINGLETON_ID).orElse(null)

    @Transactional
    fun upsert(request: UpsertTenantBillingDefaultsRequest, userEmail: String): TenantBillingDefaults {
        val entity = TenantBillingDefaults(
            id = TenantBillingDefaults.SINGLETON_ID,
            defaultStorageRatePerCbmDay = request.defaultStorageRatePerCbmDay,
            defaultInboundCbmRate = request.defaultInboundCbmRate,
            defaultOutboundCbmRate = request.defaultOutboundCbmRate,
            defaultMonthlyMinimum = request.defaultMonthlyMinimum,
            freighaiStorageChargeTypeId = request.freighaiStorageChargeTypeId,
            freighaiInboundMovementChargeTypeId = request.freighaiInboundMovementChargeTypeId,
            freighaiOutboundMovementChargeTypeId = request.freighaiOutboundMovementChargeTypeId,
            updatedAt = Instant.now(),
            updatedBy = userEmail
        )
        val saved = repository.save(entity)
        logger.info("TenantBillingDefaults upserted by={}", userEmail)
        return saved
    }
}
