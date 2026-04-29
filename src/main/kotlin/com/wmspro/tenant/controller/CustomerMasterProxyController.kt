package com.wmspro.tenant.controller

import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.service.ClientAccountsListResponse
import com.wmspro.tenant.service.CustomerMasterProxyService
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * CustomerMasterProxyController — replaces the WMS frontend's leadtorev calls
 * by exposing the same paths under the WMS gateway. The frontend's
 * `costMgmtApis.js` (Phase 6) just changes its base URL; the path scheme,
 * methods, and response shapes stay identical.
 *
 * Mounted at /clients so the gateway's /clients/[anything] route catches it.
 */
@RestController
@RequestMapping("/clients")
@Tag(name = "Customer Master Proxy", description = "FreighAi-backed customer master endpoints")
class CustomerMasterProxyController(
    private val customerMasterProxyService: CustomerMasterProxyService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** GET /clients/get/by-id/{id} */
    @GetMapping("/get/by-id/{id}")
    fun getClientById(@PathVariable id: Int): ResponseEntity<ApiResponse<Map<String, Any?>?>> {
        logger.debug("GET /clients/get/by-id/{}", id)
        val data = customerMasterProxyService.getClientById(id)
        return if (data == null) {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("Client not found: $id"))
        } else {
            ResponseEntity.ok(ApiResponse.success(data, "Client retrieved"))
        }
    }

    /** POST /clients/accounts/get/crm/get-client-accounts-list?page=&size=&searchPattern= */
    @PostMapping("/accounts/get/crm/get-client-accounts-list")
    fun getClientAccountsList(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) searchPattern: String?,
        @RequestHeader("Authorization") authToken: String
    ): ResponseEntity<ClientAccountsListResponse> {
        logger.debug("POST /clients/accounts/get/crm/get-client-accounts-list page={} size={}", page, size)
        val response = customerMasterProxyService.getClientAccountsList(page, size, searchPattern, authToken)
        return ResponseEntity.ok(response)
    }

    /** GET /clients/accounts/get/distinct/account-type */
    @GetMapping("/accounts/get/distinct/account-type")
    fun getDistinctAccountTypes(): ResponseEntity<Map<String, Any?>> {
        logger.debug("GET /clients/accounts/get/distinct/account-type")
        return ResponseEntity.ok(customerMasterProxyService.getDistinctAccountTypes())
    }

    /** POST /clients/accounts/add */
    @PostMapping("/accounts/add")
    fun createCustomerAccount(
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("Authorization") authToken: String
    ): ResponseEntity<Map<String, Any?>> {
        logger.info("POST /clients/accounts/add - name={}", body["name"])
        return ResponseEntity.ok(customerMasterProxyService.createCustomerAccount(body, authToken))
    }

    /** GET /clients/accounts/get/by-id?accountId={long} */
    @GetMapping("/accounts/get/by-id")
    fun getCustomerAccountById(
        @RequestParam accountId: Long,
        @RequestHeader("Authorization") authToken: String
    ): ResponseEntity<Map<String, Any?>> {
        logger.debug("GET /clients/accounts/get/by-id?accountId={}", accountId)
        val data = customerMasterProxyService.getCustomerAccountById(accountId, authToken)
        return if (data == null) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                mapOf("success" to false, "message" to "Customer not found", "data" to null)
            )
        } else {
            ResponseEntity.ok(data)
        }
    }

    /** PUT /clients/accounts/update?clientId={long} body=data */
    @PutMapping("/accounts/update")
    fun updateCustomerAccount(
        @RequestParam(required = false) clientId: Long?,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("Authorization") authToken: String
    ): ResponseEntity<Map<String, Any?>> {
        logger.info("PUT /clients/accounts/update - clientId={}", clientId)
        return ResponseEntity.ok(customerMasterProxyService.updateCustomerAccount(clientId, body, authToken))
    }
}
