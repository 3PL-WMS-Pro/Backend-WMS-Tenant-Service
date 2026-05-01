package com.wmspro.tenant.billing.freighai

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.external.freighai.client.FreighAiChargeTypeClient
import com.wmspro.common.external.freighai.dto.FreighAiChargeType
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.Cacheable
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Proxies FreighAi's master-data ChargeType endpoint to the WMS frontend so
 * the admin Service-Catalog editor and (Phase 2) BillingProfile editor can
 * populate ChargeType pickers without the frontend needing direct FreighAi
 * access. The client-side flow stays uniform: WMS frontend → WMS gateway →
 * WMS Tenant Service → FreighAi gateway.
 *
 * Cached for 5 minutes via the @Cacheable layer below — ChargeType lists
 * rarely change (manual customer edits in FreighAi UI), so an aggressive
 * cache here saves dozens of round-trips per session.
 *
 * IMPORTANT: cache key includes the auth token because FreighAi derives
 * tenant from the JWT — different tenants returning different lists must
 * not share cache entries.
 */
@RestController
@RequestMapping("/api/v1/freighai/charge-types")
class FreighAiChargeTypeProxyController(
    private val proxy: FreighAiChargeTypeProxyService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(
        @RequestParam(name = "activeOnly", defaultValue = "true") activeOnly: Boolean,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<List<FreighAiChargeType>>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        if (authToken.isBlank()) {
            return ResponseEntity.status(401).body(ApiResponse.error("Authorization header missing"))
        }
        val data = proxy.listChargeTypes(authToken, activeOnly)
        logger.debug("FreighAi charge-types proxy returned {} entries (activeOnly={})", data.size, activeOnly)
        return ResponseEntity.ok(ApiResponse.success(data, "FreighAi charge types retrieved"))
    }
}

/**
 * Thin caching wrapper around [FreighAiChargeTypeClient]. Spring's @Cacheable
 * lives on a service bean (not the controller) by convention — keeps the
 * caching concern out of the controller's HTTP-handling code.
 *
 * Cache name `freighaiChargeTypes` is added to the tenant-service Caffeine
 * config (see WmsTenantServiceApplication / application.yml).
 */
@Component
class FreighAiChargeTypeProxyService(
    private val client: FreighAiChargeTypeClient
) {
    @Cacheable(value = ["freighaiChargeTypes"], key = "#authToken + '|' + #activeOnly")
    fun listChargeTypes(authToken: String, activeOnly: Boolean): List<FreighAiChargeType> =
        client.listChargeTypes(authToken, activeOnly)
}
