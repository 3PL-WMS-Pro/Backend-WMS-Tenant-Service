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
                storageCostTreatment = validateTreatment(request.storageCostTreatment),
                inboundCostTreatment = validateTreatment(request.inboundCostTreatment),
                outboundCostTreatment = validateTreatment(request.outboundCostTreatment),
                updatedAt = Instant.now(),
                updatedBy = userEmail
            )
        )
        logger.info("TenantOperationalCosts upserted by={}", userEmail)
        return saved
    }

    private fun validateTreatment(value: String): String {
        val normalized = value.trim().uppercase()
        require(normalized in setOf("PARTNER_INVOICE", "INTERNAL_STANDARD")) {
            "Cost treatment must be PARTNER_INVOICE or INTERNAL_STANDARD"
        }
        return normalized
    }
}
