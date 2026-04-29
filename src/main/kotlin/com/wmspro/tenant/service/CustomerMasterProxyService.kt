package com.wmspro.tenant.service

import com.wmspro.common.external.freighai.client.FreighAiCustomerClient
import com.wmspro.common.external.freighai.dto.FreighAiCustomerListItem
import com.wmspro.tenant.dto.GetOrAssignRequestItem
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

/**
 * CustomerMasterProxyService — wraps FreighAi customer endpoints + handles the
 * Long ↔ String ID translation so the WMS frontend's existing 7 functions
 * (loginUserAPI handled separately by AuthProxy) don't have to change.
 *
 * For each customer record returned from FreighAi, the service uses
 * AccountIdMappingService to either look up the existing Long mapping or mint a
 * synthetic Long (≥ 1,000,000) on first encounter.
 *
 * Inbound requests must include an Authorization Bearer token (the FreighAi JWT)
 * which is forwarded to FreighAi as-is. The WMS gateway already validates this
 * token (Phase 5 secret swap).
 */
@Service
class CustomerMasterProxyService(
    private val freighAiCustomerClient: FreighAiCustomerClient,
    private val accountIdMappingService: AccountIdMappingService,
    private val tenantRepository: TenantDatabaseMappingRepository,
    private val restTemplate: RestTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.base-url:https://api.freighai.com}")
    private lateinit var freighAiBaseUrl: String

    /**
     * GET /clients/get/by-id/{id} — returns WMS-side tenant info from
     * tenant_database_mappings (central DB). No FreighAi call.
     */
    fun getClientById(clientId: Int): Map<String, Any?>? {
        val tenant = tenantRepository.findById(clientId).orElse(null) ?: return null
        return mapOf(
            "clientId" to tenant.clientId,
            "tenantName" to tenant.tenantName,
            "status" to tenant.status.name,
            "freighaiTenantId" to tenant.freighaiTenantId
        )
    }

    /**
     * POST /clients/accounts/get/crm/get-client-accounts-list?page=&size=&searchPattern=
     * Returns leadtorev-shaped response with Long IDs minted via AccountIdMappingService.
     */
    fun getClientAccountsList(
        page: Int,
        size: Int,
        searchPattern: String?,
        authToken: String
    ): ClientAccountsListResponse {
        val customerPage = freighAiCustomerClient.listCustomers(page, size, searchPattern, authToken)
            ?: return ClientAccountsListResponse(
                success = false,
                message = "Failed to fetch customers from FreighAi",
                totalCount = 0,
                totalContactCount = 0,
                data = emptyList()
            )

        val rows = customerPage.content
        if (rows.isEmpty()) {
            return ClientAccountsListResponse(
                success = true,
                message = "No customers found",
                totalCount = customerPage.totalElements.toInt(),
                totalContactCount = 0,
                data = emptyList()
            )
        }

        // Mint or fetch Longs for every customer in the page
        val mintItems = rows.map { GetOrAssignRequestItem(it.customerId, it.accountCode ?: "") }
        val freighaiToLong = accountIdMappingService.batchGetOrAssign(mintItems)

        val data = rows.mapNotNull { item ->
            val long = freighaiToLong[item.customerId] ?: return@mapNotNull null
            buildAccountListItem(item, long)
        }

        return ClientAccountsListResponse(
            success = true,
            message = null,
            totalCount = customerPage.totalElements.toInt(),
            totalContactCount = data.sumOf { (it["contacts"] as? List<*>)?.size ?: 0 },
            data = data
        )
    }

    /**
     * GET /clients/accounts/get/distinct/account-type — returns hardcoded enum values.
     * No FreighAi call (FreighAi has 2 customer types as enum: DIRECT_CUSTOMER, FREIGHT_FORWARDER).
     */
    fun getDistinctAccountTypes(): Map<String, Any?> = mapOf(
        "success" to true,
        "message" to null,
        "data" to listOf("DIRECT_CUSTOMER", "FREIGHT_FORWARDER")
    )

    /**
     * GET /clients/accounts/get/by-id?accountId={long}
     * Resolves the Long → FreighAi customerId via mapping, fetches detail, returns leadtorev shape.
     */
    fun getCustomerAccountById(accountId: Long, authToken: String): Map<String, Any?>? {
        val mapping = accountIdMappingService.batchGetByLeadtorevIds(listOf(accountId))[accountId]
            ?: run {
                logger.warn("No mapping found for accountId={}; cannot fetch detail", accountId)
                return null
            }

        // Use FreighAi batch-by-ids for a single ID (avoids needing a separate GET helper).
        val customers = freighAiCustomerClient.batchByIds(listOf(mapping.freighaiCustomerId), authToken)
        val item = customers[mapping.freighaiCustomerId] ?: return null

        return mapOf(
            "success" to true,
            "message" to null,
            "data" to buildAccountListItem(item, accountId)
        )
    }

    /**
     * POST /clients/accounts/add — body translated to FreighAi's CreateCustomerRequest shape
     * by `mapCreateBody`, then forwarded. After creation, mints a Long for the new FreighAi
     * customer and returns the leadtorev-shaped response.
     */
    fun createCustomerAccount(body: Map<String, Any?>, authToken: String): Map<String, Any?> {
        val freighaiBody = mapCreateBody(body)
        val response = postToFreighAi("/api/v1/customers", freighaiBody, authToken)
            ?: return mapOf("success" to false, "message" to "FreighAi create failed", "data" to null)

        val customerData = (response["data"] as? Map<*, *>)?.get("customer") as? Map<*, *>
            ?: return mapOf("success" to false, "message" to "FreighAi response missing customer data", "data" to null)

        val customerId = customerData["customerId"] as? String
            ?: return mapOf("success" to false, "message" to "FreighAi response missing customerId", "data" to null)
        val accountCode = customerData["accountCode"] as? String ?: ""

        val long = accountIdMappingService.batchGetOrAssign(
            listOf(GetOrAssignRequestItem(customerId, accountCode))
        )[customerId]
            ?: return mapOf("success" to false, "message" to "Failed to assign WMS Long for new customer", "data" to null)

        return mapOf(
            "success" to true,
            "message" to "Customer created",
            "data" to mapOf(
                "id" to long,
                "customerId" to customerId,
                "accountCode" to accountCode,
                "name" to customerData["name"],
                "type" to customerData["type"]
            )
        )
    }

    /**
     * PUT /clients/accounts/update?clientId=... body=data
     * Best-effort: locates accountId in the body / params, resolves to FreighAi customerId,
     * forwards an UpdateCustomerRequest-shaped body to FreighAi.
     */
    fun updateCustomerAccount(accountId: Long?, body: Map<String, Any?>, authToken: String): Map<String, Any?> {
        val resolvedAccountId = accountId
            ?: (body["id"] as? Number)?.toLong()
            ?: (body["accountId"] as? Number)?.toLong()
            ?: return mapOf("success" to false, "message" to "accountId not provided", "data" to null)

        val mapping = accountIdMappingService.batchGetByLeadtorevIds(listOf(resolvedAccountId))[resolvedAccountId]
            ?: return mapOf("success" to false, "message" to "No mapping found for accountId=$resolvedAccountId", "data" to null)

        val freighaiBody = mapUpdateBody(body)
        val url = "/api/v1/customers/${mapping.freighaiCustomerId}"
        val response = putToFreighAi(url, freighaiBody, authToken)
            ?: return mapOf("success" to false, "message" to "FreighAi update failed", "data" to null)

        return mapOf(
            "success" to true,
            "message" to "Customer updated",
            "data" to (response["data"] ?: emptyMap<String, Any?>())
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build the leadtorev-shaped customer-list item that the frontend's table /
     * dropdown components consume. `id` is the WMS Long; `accountCode`, `account.name`,
     * and primaryContact-derived fields come from FreighAi.
     */
    private fun buildAccountListItem(item: FreighAiCustomerListItem, long: Long): Map<String, Any?> = mapOf(
        "id" to long,
        "customerId" to item.customerId,
        "accountCode" to (item.accountCode ?: ""),
        "createDate" to item.createdAt,
        "account" to mapOf(
            "name" to item.name,
            "type" to item.type,
            "emailId" to item.primaryContactEmail,
            "contactNo" to null
        ),
        "contacts" to if (item.primaryContactName != null || item.primaryContactEmail != null) {
            listOf(
                mapOf(
                    "name" to item.primaryContactName,
                    "email" to item.primaryContactEmail,
                    "defaultContact" to true
                )
            )
        } else emptyList<Map<String, Any?>>(),
        "verificationStatus" to null,
        "systemDriven" to null,
        "clientId" to (TenantContext.getCurrentTenant()?.toIntOrNull()),
        "lastEngaged" to item.updatedAt
    )

    /** Best-effort translation: leadtorev create-account body → FreighAi CreateCustomerRequest. */
    private fun mapCreateBody(body: Map<String, Any?>): Map<String, Any?> {
        val name = body["name"] as? String ?: body["accountName"] as? String ?: ""
        val type = (body["type"] as? String) ?: "DIRECT_CUSTOMER"
        val contactName = body["contactName"] as? String ?: body["primaryContactName"] as? String ?: name
        val contactEmail = body["contactEmail"] as? String ?: body["emailId"] as? String
        val contactPhone = body["contactNumber"] as? String ?: body["contactNo"] as? String

        val accountOwnerId = body["accountOwnerId"] as? String ?: "system"

        return mapOf(
            "name" to name,
            "type" to type,
            "currency" to (body["currency"] as? String ?: "AED"),
            "accountOwnerId" to accountOwnerId,
            "contacts" to listOf(
                mapOf(
                    "name" to contactName,
                    "emails" to (if (contactEmail != null) listOf(contactEmail) else null),
                    "phones" to (if (contactPhone != null) listOf(contactPhone) else null),
                    "isPrimary" to true,
                    "isActive" to true
                )
            )
        ).filterValues { it != null }
    }

    /** Best-effort translation: leadtorev update-account body → FreighAi UpdateCustomerRequest. */
    private fun mapUpdateBody(body: Map<String, Any?>): Map<String, Any?> {
        // Only forward fields FreighAi's UpdateCustomerRequest accepts, all optional.
        val passThrough = listOf("name", "type", "tier", "industry", "website", "taxId", "notes", "isAtRisk")
        return body.filter { (k, _) -> k in passThrough }
    }

    private fun postToFreighAi(path: String, body: Any, authToken: String): Map<String, Any?>? = try {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, if (authToken.startsWith("Bearer ", true)) authToken else "Bearer $authToken")
        }
        @Suppress("UNCHECKED_CAST")
        restTemplate.exchange("$freighAiBaseUrl$path", HttpMethod.POST, HttpEntity(body, headers), Map::class.java).body as Map<String, Any?>?
    } catch (e: Exception) {
        logger.error("FreighAi POST {} failed", path, e)
        null
    }

    private fun putToFreighAi(path: String, body: Any, authToken: String): Map<String, Any?>? = try {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, if (authToken.startsWith("Bearer ", true)) authToken else "Bearer $authToken")
        }
        @Suppress("UNCHECKED_CAST")
        restTemplate.exchange("$freighAiBaseUrl$path", HttpMethod.PUT, HttpEntity(body, headers), Map::class.java).body as Map<String, Any?>?
    } catch (e: Exception) {
        logger.error("FreighAi PUT {} failed", path, e)
        null
    }
}

/**
 * Response shape for the customer-list endpoint, leadtorev-shaped.
 */
data class ClientAccountsListResponse(
    val success: Boolean,
    val message: String?,
    val totalCount: Int,
    val totalContactCount: Int,
    val data: List<Map<String, Any?>>
)
