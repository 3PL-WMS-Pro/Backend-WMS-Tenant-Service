package com.wmspro.tenant.billing.costs

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class TenantOperationalCostsService(
    private val repository: TenantOperationalCostsRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun findOrNull(): TenantOperationalCosts? =
        repository.findById(TenantOperationalCosts.SINGLETON_ID).orElse(null)

    @Transactional
    fun upsert(request: UpsertTenantOperationalCostsRequest, userEmail: String): TenantOperationalCosts {
        val saved = repository.save(
            TenantOperationalCosts(
                id = TenantOperationalCosts.SINGLETON_ID,
                baseStorageCostPerCbmDay = request.baseStorageCostPerCbmDay,
                baseInboundCostPerCbm = request.baseInboundCostPerCbm,
                baseOutboundCostPerCbm = request.baseOutboundCostPerCbm,
                updatedAt = Instant.now(),
                updatedBy = userEmail
            )
        )
        logger.info("TenantOperationalCosts upserted by={}", userEmail)
        return saved
    }
}
