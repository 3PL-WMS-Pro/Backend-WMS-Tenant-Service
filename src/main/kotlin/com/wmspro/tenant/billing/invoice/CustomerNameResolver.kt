package com.wmspro.tenant.billing.invoice

import com.wmspro.common.external.freighai.client.FreighAiCustomerClient
import com.wmspro.tenant.repository.AccountIdMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Phase G.1 — server-side batch resolution of WMS customerId → customer
 * name. Powers the customer-name columns on the WMS Invoices list, the
 * invoice detail header, and the Bill Runs expanded chips so the
 * frontend doesn't have to paginate through 1,300+ customer records
 * client-side just to render names.
 *
 * Flow:
 *   1. Look up [com.wmspro.tenant.model.AccountIdMapping] rows for the
 *      requested WMS Long IDs to get the paired FreighAi customer IDs.
 *   2. Batch-fetch the FreighAi customer documents via
 *      [FreighAiCustomerClient.batchByIds] (single round-trip).
 *   3. Build a Map<Long, String> the caller can splice onto its response
 *      DTOs.
 *
 * Failures are degraded gracefully — a customerId with no mapping or a
 * FreighAi outage simply yields no entry in the returned map; callers
 * render an empty / placeholder string rather than failing the request.
 */
@Component
class CustomerNameResolver(
    private val accountIdMappingRepository: AccountIdMappingRepository,
    private val freighAiCustomerClient: FreighAiCustomerClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun resolve(wmsCustomerIds: Set<Long>, authToken: String): Map<Long, String> {
        if (wmsCustomerIds.isEmpty() || authToken.isBlank()) return emptyMap()
        return try {
            val mappings = accountIdMappingRepository.findAllById(wmsCustomerIds).toList()
            val freighaiIds = mappings.mapNotNull { it.freighaiCustomerId }.distinct()
            if (freighaiIds.isEmpty()) return emptyMap()
            val freighaiCustomers = freighAiCustomerClient.batchByIds(freighaiIds, authToken)
            mappings.mapNotNull { m ->
                val name = freighaiCustomers[m.freighaiCustomerId]?.name
                if (name.isNullOrBlank()) null else m.id to name
            }.toMap()
        } catch (e: Exception) {
            logger.warn("CustomerNameResolver: lookup failed for {} ids — returning empty map", wmsCustomerIds.size, e)
            emptyMap()
        }
    }
}
