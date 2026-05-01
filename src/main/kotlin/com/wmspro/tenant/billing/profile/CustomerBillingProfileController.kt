package com.wmspro.tenant.billing.profile

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * `/api/v1/billing-profiles/{customerId}` — per-customer billing config.
 *
 * Hosts both a full upsert (the WMS Billing-tab UI's primary write path) and
 * granular project/subscription endpoints (for future programmatic callers
 * and possible UI affordances). Uniqueness, FreighAi ChargeType validity,
 * and ServiceCatalog cross-references are all checked in
 * [CustomerBillingProfileService].
 *
 * `IllegalArgumentException` → 400, `IllegalStateException` → 409, missing
 * profile on a granular endpoint → 404. The controller's catch-block layout
 * mirrors [com.wmspro.tenant.billing.catalog.ServiceCatalogController] so
 * future readers see one shape across the billing module.
 */
@RestController
@RequestMapping("/api/v1/billing-profiles")
class CustomerBillingProfileController(
    private val service: CustomerBillingProfileService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{customerId}")
    fun get(
        @PathVariable customerId: Long
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        val profile = service.findByCustomerId(customerId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error("No billing profile for customerId=$customerId")
            )
        return ResponseEntity.ok(ApiResponse.success(profile.toResponse(), "Billing profile retrieved"))
    }

    @PutMapping("/{customerId}")
    fun upsert(
        @PathVariable customerId: Long,
        @Valid @RequestBody request: UpsertCustomerBillingProfileRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, authToken ->
            val saved = service.upsert(customerId, request, userEmail, authToken)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Billing profile saved"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Granular project endpoints
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/{customerId}/projects")
    fun addProject(
        @PathVariable customerId: Long,
        @Valid @RequestBody request: AddProjectRateRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.addProject(customerId, request, userEmail)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Project added")
            )
        }
    }

    @PutMapping("/{customerId}/projects/{projectCode}")
    fun updateProject(
        @PathVariable customerId: Long,
        @PathVariable projectCode: String,
        @Valid @RequestBody request: UpdateProjectRateRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.updateProject(customerId, projectCode, request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Project updated"))
        }
    }

    @DeleteMapping("/{customerId}/projects/{projectCode}")
    fun deactivateProject(
        @PathVariable customerId: Long,
        @PathVariable projectCode: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.deactivateProject(customerId, projectCode, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Project deactivated"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Granular subscription endpoints
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/{customerId}/services")
    fun addService(
        @PathVariable customerId: Long,
        @Valid @RequestBody request: AddServiceSubscriptionRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.addServiceSubscription(customerId, request, userEmail)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Service subscription added")
            )
        }
    }

    @PutMapping("/{customerId}/services/{serviceCode}")
    fun updateService(
        @PathVariable customerId: Long,
        @PathVariable serviceCode: String,
        @Valid @RequestBody request: UpdateServiceSubscriptionRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.updateServiceSubscription(customerId, serviceCode, request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Service subscription updated"))
        }
    }

    @DeleteMapping("/{customerId}/services/{serviceCode}")
    fun deactivateService(
        @PathVariable customerId: Long,
        @PathVariable serviceCode: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.deactivateServiceSubscription(customerId, serviceCode, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Service subscription deactivated"))
        }
    }

    @PostMapping("/{customerId}/billing-enabled")
    fun setBillingEnabled(
        @PathVariable customerId: Long,
        @RequestBody request: SetBillingEnabledRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        return safeMutation(httpRequest) { userEmail, _ ->
            val saved = service.setBillingEnabled(customerId, request.enabled, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Billing enabled flag updated"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Centralised exception → HTTP-status mapping. Same shape as
     * ServiceCatalogController; pulled into a helper here because every
     * mutation endpoint repeats it. Lambda receives userEmail + authToken so
     * the caller doesn't have to extract them.
     */
    private fun safeMutation(
        httpRequest: HttpServletRequest,
        block: (userEmail: String, authToken: String) -> ResponseEntity<ApiResponse<CustomerBillingProfileResponse>>
    ): ResponseEntity<ApiResponse<CustomerBillingProfileResponse>> {
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return try {
            block(userEmail, authToken)
        } catch (e: IllegalArgumentException) {
            logger.warn("Billing profile validation/not-found error: {}", e.message)
            // 404 for "not found" wording vs 400 for other validation failures.
            val status = if (e.message?.contains("not found", ignoreCase = true) == true)
                HttpStatus.NOT_FOUND else HttpStatus.BAD_REQUEST
            ResponseEntity.status(status).body(ApiResponse.error(e.message ?: "Invalid request"))
        } catch (e: IllegalStateException) {
            logger.warn("Billing profile conflict: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("Billing profile internal error", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    private fun CustomerBillingProfile.toResponse() = CustomerBillingProfileResponse(
        customerId = customerId,
        defaultCbmRatePerDay = defaultCbmRatePerDay,
        defaultInboundCbmRate = defaultInboundCbmRate,
        defaultOutboundCbmRate = defaultOutboundCbmRate,
        defaultMonthlyMinimum = defaultMonthlyMinimum,
        projects = projects.map { it.toResponse() },
        serviceSubscriptions = serviceSubscriptions.map { it.toResponse() },
        freighaiStorageChargeTypeId = freighaiStorageChargeTypeId,
        freighaiInboundMovementChargeTypeId = freighaiInboundMovementChargeTypeId,
        freighaiOutboundMovementChargeTypeId = freighaiOutboundMovementChargeTypeId,
        billingEnabled = billingEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
        updatedBy = updatedBy
    )

    private fun ProjectRate.toResponse() = ProjectRateResponse(
        projectCode = projectCode,
        label = label,
        cbmRatePerDay = cbmRatePerDay,
        inboundCbmRate = inboundCbmRate,
        outboundCbmRate = outboundCbmRate,
        isActive = isActive
    )

    private fun ServiceSubscription.toResponse() = ServiceSubscriptionResponse(
        serviceCode = serviceCode,
        customRatePerUnit = customRatePerUnit,
        isActive = isActive
    )
}
