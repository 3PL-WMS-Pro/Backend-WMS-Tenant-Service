package com.wmspro.tenant.billing.invoice.cascade

import com.wmspro.common.billing.ReserveBillingSourceClaimRequest
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.servicelog.ServiceLogBillingClaimService
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
    private val serviceLogRepository: ServiceLogRepository,
    private val serviceLogClaimService: ServiceLogBillingClaimService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Defaults match the deployed topology:
    //   - inbound-service runs on :6017 with `server.servlet.context-path: /api`
    //   - order-service   runs on :6018 with no context-path
    // Override via SPRING env in non-default deployments.
    @Value("\${services.inbound-service.url:http://localhost:6017/api}")
    private lateinit var inboundServiceUrl: String

    @Value("\${services.order-service.url:http://localhost:6018}")
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
        serviceLogIds: List<String>,
        authToken: String
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
                    billingMonth = billingMonth,
                    authToken = authToken
                )
            ) grnFails += id
        }

        fulfillmentIds.forEach { id ->
            if (!setLockExternal(
                    serviceUrl = orderServiceUrl,
                    pathTemplate = "/api/v1/internal/orders/{id}/billing-lock",
                    id = id,
                    billingInvoiceId = billingInvoiceId,
                    billingMonth = billingMonth,
                    authToken = authToken
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
        serviceLogIds: List<String>,
        authToken: String
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
                    id = id,
                    authToken = authToken
                )
            ) grnFails += id
        }

        fulfillmentIds.forEach { id ->
            if (!clearLockExternal(
                    serviceUrl = orderServiceUrl,
                    pathTemplate = "/api/v1/internal/orders/{id}/billing-lock",
                    id = id,
                    authToken = authToken
                )
            ) ginFails += id
        }

        return CascadeOutcome(grnFails, ginFails, svcFails)
    }

    /** V1-only claim transport.  Callers must never reinterpret a failure as permission to use legacy locks. */
    fun reserveClaims(
        receivingRecords: List<BillingClaimTarget>,
        fulfillmentRecords: List<BillingClaimTarget>,
        serviceLogs: List<BillingClaimTarget>,
        requestFor: (BillingClaimTarget) -> ReserveBillingSourceClaimRequest,
        authToken: String
    ): ClaimCascadeOutcome {
        val reserved = mutableListOf<ClaimedBillingSource>()
        val failures = mutableListOf<String>()
        fun reserveExternal(kind: BillingSourceKind, target: BillingClaimTarget, base: String, path: String) {
            val ok = postClaim(base + path.replace("{id}", target.id), requestFor(target), authToken)
            if (ok) reserved += ClaimedBillingSource(kind, target) else failures += "${kind.name}:${target.id}"
        }
        receivingRecords.sortedBy { it.id }.forEach {
            reserveExternal(BillingSourceKind.GRN, it, inboundServiceUrl, "/api/v1/internal/receiving-records/{id}/billing-claim")
        }
        fulfillmentRecords.sortedBy { it.id }.forEach {
            reserveExternal(BillingSourceKind.GIN, it, orderServiceUrl, "/api/v1/internal/orders/{id}/billing-claim")
        }
        serviceLogs.sortedBy { it.id }.forEach { target ->
            try {
                serviceLogClaimService.reserve(target.id, requestFor(target))
                reserved += ClaimedBillingSource(BillingSourceKind.SERVICE_LOG, target)
            } catch (e: Exception) {
                logger.error("V1 ServiceLog reserve failed for {}", target.id, e)
                failures += "SERVICE_LOG:${target.id}"
            }
        }
        return ClaimCascadeOutcome(reserved, failures)
    }

    fun transitionClaims(
        claims: List<ClaimedBillingSource>,
        request: TransitionBillingSourceClaimRequest,
        commit: Boolean,
        authToken: String
    ): List<String> {
        val failures = mutableListOf<String>()
        claims.forEach { claimed ->
            val suffix = if (commit) "/commit" else "/release"
            val ok = when (claimed.kind) {
                BillingSourceKind.GRN -> postClaim(
                    inboundServiceUrl + "/api/v1/internal/receiving-records/${claimed.target.id}/billing-claim$suffix",
                    request, authToken
                )
                BillingSourceKind.GIN -> postClaim(
                    orderServiceUrl + "/api/v1/internal/orders/${claimed.target.id}/billing-claim$suffix",
                    request, authToken
                )
                BillingSourceKind.SERVICE_LOG -> try {
                    if (commit) serviceLogClaimService.commit(claimed.target.id, request)
                    else serviceLogClaimService.release(claimed.target.id, request)
                    true
                } catch (e: Exception) {
                    logger.error("V1 ServiceLog claim transition failed for {}", claimed.target.id, e)
                    false
                }
            }
            if (!ok) failures += "${claimed.kind.name}:${claimed.target.id}"
        }
        return failures
    }

    private fun postClaim(url: String, body: Any, authToken: String): Boolean = try {
        restTemplate.exchange(
            url, HttpMethod.POST, HttpEntity(body, buildInternalHeaders(authToken)), String::class.java
        ).statusCode.is2xxSuccessful
    } catch (e: Exception) {
        logger.error("V1 billing claim call failed url={}", url, e)
        false
    }

    private fun setLockExternal(
        serviceUrl: String,
        pathTemplate: String,
        id: String,
        billingInvoiceId: String,
        billingMonth: String,
        authToken: String
    ): Boolean {
        val url = serviceUrl + pathTemplate.replace("{id}", id)
        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                HttpEntity(
                    mapOf("billingInvoiceId" to billingInvoiceId, "billingMonth" to billingMonth),
                    buildInternalHeaders(authToken)
                ),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("setLockExternal failed url={} id={}", url, id, e)
            false
        }
    }

    private fun clearLockExternal(serviceUrl: String, pathTemplate: String, id: String, authToken: String): Boolean {
        val url = serviceUrl + pathTemplate.replace("{id}", id)
        return try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.DELETE,
                HttpEntity<Void>(buildInternalHeaders(authToken)),
                String::class.java
            )
            response.statusCode.is2xxSuccessful
        } catch (e: Exception) {
            logger.error("clearLockExternal failed url={} id={}", url, id, e)
            false
        }
    }

    private fun buildInternalHeaders(authToken: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        accept = listOf(MediaType.APPLICATION_JSON)
        // Phase F.1: inbound + order TenantInterceptors only accept
        // `X-Client-ID` / `X-Tenant-ID` / `X-Tenant-Id`. The previous
        // `X-Client` header was silently rejected → 401. Use `X-Tenant-Id`
        // (the same name the frontend sends) so the cascade is consistent
        // with the rest of the stack.
        TenantContext.getCurrentTenant()?.let { set("X-Tenant-Id", it) }
        // Phase F: forward the original request's JWT so downstream service
        // auth filters accept the cascade call.
        if (authToken.isNotBlank()) {
            set(
                HttpHeaders.AUTHORIZATION,
                if (authToken.startsWith("Bearer ", ignoreCase = true)) authToken else "Bearer $authToken"
            )
        }
    }
}

data class BillingClaimTarget(val id: String, val sourceLineId: String)
enum class BillingSourceKind { GRN, GIN, SERVICE_LOG }
data class ClaimedBillingSource(val kind: BillingSourceKind, val target: BillingClaimTarget)
data class ClaimCascadeOutcome(val reserved: List<ClaimedBillingSource>, val failures: List<String>) {
    fun isAllSuccess(expected: Int): Boolean = failures.isEmpty() && reserved.size == expected
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
