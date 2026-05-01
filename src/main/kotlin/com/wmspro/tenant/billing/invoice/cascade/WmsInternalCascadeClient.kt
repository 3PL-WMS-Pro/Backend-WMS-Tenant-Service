package com.wmspro.tenant.billing.invoice.cascade

import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.servicelog.ServiceLogRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.time.Instant

/**
 * WmsInternalCascadeClient — sets / clears `billingInvoiceId` on the three
 * lockable entity types whose data feeds the billing engine:
 *
 *   1. ServiceLog            (local — Tenant Service owns)
 *   2. ReceivingRecord (GRN) (Inbound Service — call its internal endpoint)
 *   3. OrderFulfillmentRequest (GIN) (Order Service — call its internal endpoint)
 *
 * Operating semantics:
 *   - **Set lock** is invoked AFTER the WmsBillingInvoice is pre-created
 *     and BEFORE the FreighAi POST. This way, if FreighAi rejects, the
 *     billing engine attempts to roll back the locks; in the worst case
 *     they stay set until admin runs a manual cancel.
 *   - **Clear lock** is invoked during cancel or rollback. Failures are
 *     logged but don't block the cancel from completing.
 *
 * Service URLs come from config (matches the same `services.inbound-service.url`
 * pattern used by `InventoryServiceClient` in Inbound Service). The
 * `X-Client` header carries the tenant id so the receiving service routes
 * the write to the correct per-tenant DB — same convention as the existing
 * `AccountIdMappingInternalController`.
 */
@Component
class WmsInternalCascadeClient(
    private val restTemplate: RestTemplate,
    private val serviceLogRepository: ServiceLogRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${services.inbound-service.url:http://localhost:6011}")
    private lateinit var inboundServiceUrl: String

    @Value("\${services.order-service.url:http://localhost:6012}")
    private lateinit var orderServiceUrl: String

    /**
     * Set `billingInvoiceId` + `billingMonth` on every record in the input
     * lists. Returns a [CascadeOutcome] enumerating successes and failures
     * per category — the caller (BillingRunService) decides whether partial
     * success is acceptable.
     */
    fun setLocks(
        billingInvoiceId: String,
        billingMonth: String,
        receivingRecordIds: List<String>,
        fulfillmentIds: List<String>,
        serviceLogIds: List<String>
    ): CascadeOutcome {
        val grnFails = mutableListOf<String>()
        val ginFails = mutableListOf<String>()
        val svcFails = mutableListOf<String>()

        // ServiceLogs — local DB write. We update by ID one-at-a-time for
        // simplicity; volume per run is small (typically <100 logs/month).
        serviceLogIds.forEach { id ->
            try {
                val log = serviceLogRepository.findById(id).orElse(null)
                if (log == null) { svcFails += id; return@forEach }
                serviceLogRepository.save(
                    log.copy(
                        billingInvoiceId = billingInvoiceId,
                        updatedAt = Instant.now()
                    )
                )
            } catch (e: Exception) {
                logger.error("Cascade set ServiceLog lock failed for {}", id, e)
                svcFails += id
            }
        }

        receivingRecordIds.forEach { id ->
            if (!setLockExternal(
                    serviceUrl = inboundServiceUrl,
                    pathTemplate = "/api/v1/internal/receiving-records/{id}/billing-lock",
                    id = id,
                    billingInvoiceId = billingInvoiceId,
                    billingMonth = billingMonth
                )
            ) grnFails += id
        }

        fulfillmentIds.forEach { id ->
            if (!setLockExternal(
                    serviceUrl = orderServiceUrl,
                    pathTemplate = "/api/v1/internal/orders/{id}/billing-lock",
                    id = id,
                    billingInvoiceId = billingInvoiceId,
                    billingMonth = billingMonth
                )
            ) ginFails += id
        }

        return CascadeOutcome(grnFails, ginFails, svcFails)
    }

    /**
     * Best-effort unlock for cancel / rollback flows. Errors are logged but
     * not fatal — cancellation should always be able to complete from the
     * WMS side. Stuck locks can be cleared manually.
     */
    fun clearLocks(
        receivingRecordIds: List<String>,
        fulfillmentIds: List<String>,
        serviceLogIds: List<String>
    ): CascadeOutcome {
        val grnFails = mutableListOf<String>()
        val ginFails = mutableListOf<String>()
        val svcFails = mutableListOf<String>()

        serviceLogIds.forEach { id ->
            try {
                val log = serviceLogRepository.findById(id).orElse(null) ?: return@forEach
                serviceLogRepository.save(
                    log.copy(
                        billingInvoiceId = null,
                        updatedAt = Instant.now()
                    )
                )
            } catch (e: Exception) {
                logger.error("Cascade clear ServiceLog lock failed for {}", id, e)
                svcFails += id
            }
        }

        receivingRecordIds.forEach { id ->
            if (!clearLockExternal(
                    serviceUrl = inboundServiceUrl,
                    pathTemplate = "/api/v1/internal/receiving-records/{id}/billing-lock",
                    id = id
                )
            ) grnFails += id
        }

        fulfillmentIds.forEach { id ->
            if (!clearLockExternal(
                    serviceUrl = orderServiceUrl,
                    pathTemplate = "/api/v1/internal/orders/{id}/billing-lock",
                    id = id
                )
            ) ginFails += id
        }

        return CascadeOutcome(grnFails, ginFails, svcFails)
    }

    private fun setLockExternal(
        serviceUrl: String,
        pathTemplate: String,
        id: String,
        billingInvoiceId: String,
        billingMonth: String
    ): Boolean {
        val url = serviceUrl + pathTemplate.replace("{id}", id)
        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                HttpEntity(
                    mapOf("billingInvoiceId" to billingInvoiceId, "billingMonth" to billingMonth),
                    buildInternalHeaders()
                ),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("setLockExternal failed url={} id={}", url, id, e)
            false
        }
    }

    private fun clearLockExternal(serviceUrl: String, pathTemplate: String, id: String): Boolean {
        val url = serviceUrl + pathTemplate.replace("{id}", id)
        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                HttpEntity<Void>(buildInternalHeaders()),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("clearLockExternal failed url={} id={}", url, id, e)
            false
        }
    }

    private fun buildInternalHeaders(): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
        // Forward the current tenant id so the receiving service binds the
        // write to the same tenant DB — same convention as the existing
        // CustomerMasterProxyService.
        TenantContext.getCurrentTenant()?.let { set("X-Client", it) }
    }
}

/**
 * `failed*` lists are empty when the cascade fully succeeded. Caller checks
 * `isAllSuccess()` and either proceeds or rolls back.
 */
data class CascadeOutcome(
    val failedReceivingRecordIds: List<String>,
    val failedFulfillmentIds: List<String>,
    val failedServiceLogIds: List<String>
) {
    fun isAllSuccess(): Boolean =
        failedReceivingRecordIds.isEmpty()
            && failedFulfillmentIds.isEmpty()
            && failedServiceLogIds.isEmpty()

    fun summary(): String = listOfNotNull(
        if (failedServiceLogIds.isNotEmpty()) "${failedServiceLogIds.size} service logs" else null,
        if (failedReceivingRecordIds.isNotEmpty()) "${failedReceivingRecordIds.size} GRNs" else null,
        if (failedFulfillmentIds.isNotEmpty()) "${failedFulfillmentIds.size} GINs" else null
    ).joinToString(", ").ifEmpty { "none" }
}
