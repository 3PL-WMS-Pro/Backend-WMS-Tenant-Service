package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.dto.AccountIdMappingDto
import com.wmspro.tenant.dto.GetOrAssignRequestItem
import com.wmspro.tenant.service.AccountIdMappingService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Internal controller for service-to-service account-id-mapping access.
 *
 * NOTE: this controller is mounted under `/api/v1/internal/account-id-mapping` (NOT
 * under `/api/v1/tenants/internal`). This routes through the regular tenant-aware
 * branch of TenantInterceptor (per-tenant DB), which is what we need because
 * `account_id_mapping` and `account_id_sequence` live in the per-tenant database
 * (e.g. wms_pro_tenant_199).
 *
 * Callers must include the `X-Client` header (WMS-internal Long tenant id, e.g. "199").
 *
 * Security model: same as InternalTenantController — relies on network-level
 * isolation (service mesh / internal network); not exposed via the external
 * API gateway.
 */
@RestController
@RequestMapping("/api/v1/internal/account-id-mapping")
@Tag(
    name = "Internal Account ID Mapping APIs",
    description = "Service-to-service APIs for translating leadtorev account IDs ↔ FreighAI customer IDs"
)
class AccountIdMappingInternalController(
    private val accountIdMappingService: AccountIdMappingService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Forward batch lookup: given a list of leadtorev account IDs (Long),
     * return their AccountIdMapping rows. Missing IDs are omitted.
     *
     * Map keys are stringified Longs (JSON object keys must be strings).
     */
    @PostMapping("/by-leadtorev-ids")
    @Operation(
        summary = "Batch lookup mappings by leadtorev account IDs",
        description = "Returns Map<leadtorevId, AccountIdMappingDto>; missing IDs omitted"
    )
    fun byLeadtorevIds(
        @RequestBody leadtorevIds: List<Long>
    ): ResponseEntity<ApiResponse<Map<String, AccountIdMappingDto>>> {
        logger.debug("POST /api/v1/internal/account-id-mapping/by-leadtorev-ids - count={}", leadtorevIds.size)
        val result = accountIdMappingService.batchGetByLeadtorevIds(leadtorevIds)
            .mapKeys { (k, _) -> k.toString() }
        return ResponseEntity.ok(
            ApiResponse.success(result, "Resolved ${result.size} of ${leadtorevIds.size} leadtorev IDs")
        )
    }

    /**
     * Reverse batch lookup: given FreighAI customer IDs, return the matching
     * leadtorev Longs. Missing FreighAI IDs are omitted (caller can pipe the
     * unresolved subset to /get-or-assign to mint synthetic Longs).
     */
    @PostMapping("/by-freighai-ids")
    @Operation(
        summary = "Reverse batch lookup mappings by FreighAI customer IDs",
        description = "Returns Map<freighaiCustomerId, leadtorevId>; missing IDs omitted"
    )
    fun byFreighaiIds(
        @RequestBody freighaiCustomerIds: List<String>
    ): ResponseEntity<ApiResponse<Map<String, Long>>> {
        logger.debug("POST /api/v1/internal/account-id-mapping/by-freighai-ids - count={}", freighaiCustomerIds.size)
        val result = accountIdMappingService.batchGetByFreighaiIds(freighaiCustomerIds)
        return ResponseEntity.ok(
            ApiResponse.success(result, "Resolved ${result.size} of ${freighaiCustomerIds.size} FreighAI IDs")
        )
    }

    /**
     * Batch get-or-assign: returns existing leadtorev Long for each FreighAI
     * customer ID; mints a synthetic Long (≥ 1,000,000) for any that don't yet
     * have a mapping. New mappings persist with `source = "synthetic"`.
     */
    @PostMapping("/get-or-assign")
    @Operation(
        summary = "Batch get-or-assign Longs for FreighAI customer IDs",
        description = "For each input item, returns the existing leadtorev Long or mints a synthetic one"
    )
    fun getOrAssign(
        @RequestBody items: List<GetOrAssignRequestItem>
    ): ResponseEntity<ApiResponse<Map<String, Long>>> {
        logger.debug("POST /api/v1/internal/account-id-mapping/get-or-assign - count={}", items.size)
        val result = accountIdMappingService.batchGetOrAssign(items)
        return ResponseEntity.ok(
            ApiResponse.success(result, "Resolved ${result.size} FreighAI IDs (some may have been minted)")
        )
    }
}
