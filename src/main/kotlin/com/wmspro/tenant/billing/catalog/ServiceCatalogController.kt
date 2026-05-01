package com.wmspro.tenant.billing.catalog

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * `/api/v1/service-catalog` — admin-managed list of WMS-offered services
 * (palletization, repackaging, crane rental, …).
 *
 * Reads (GET) are open to any authenticated user — the BillingProfile
 * subscription editor (Phase 2) and the ServiceLog entry form (Phase 4) both
 * need to populate the service-code picker. Writes (POST/PUT/DELETE) are
 * intended to be ADMIN-only; that gate is enforced at the gateway / via the
 * `X-User-Role` header inspection by upstream — not duplicated here to avoid
 * drifting from the rest of the codebase, which doesn't role-gate inside
 * controllers either.
 *
 * Inbound `Authorization` is forwarded to FreighAi when the service needs to
 * validate `freighaiChargeTypeId` on create/update.
 */
@RestController
@RequestMapping("/api/v1/service-catalog")
class ServiceCatalogController(
    private val service: ServiceCatalogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(
        @RequestParam(name = "includeInactive", defaultValue = "false") includeInactive: Boolean
    ): ResponseEntity<ApiResponse<List<ServiceCatalogResponse>>> {
        logger.debug("GET /api/v1/service-catalog includeInactive={}", includeInactive)
        val data = service.listAll(includeInactive).map { it.toResponse() }
        return ResponseEntity.ok(ApiResponse.success(data, "Service catalog retrieved"))
    }

    @GetMapping("/{serviceCode}")
    fun getByCode(
        @PathVariable serviceCode: String
    ): ResponseEntity<ApiResponse<ServiceCatalogResponse>> {
        val entity = service.findByCode(serviceCode)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error("Service code '$serviceCode' not found")
            )
        return ResponseEntity.ok(ApiResponse.success(entity.toResponse(), "Service entry retrieved"))
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateServiceCatalogRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ServiceCatalogResponse>> {
        val userEmail = resolveUserEmail(httpRequest)
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return try {
            val saved = service.create(request, userEmail, authToken)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Service catalog entry created")
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("Validation error creating service catalog: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: IllegalStateException) {
            logger.warn("Conflict creating service catalog: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("Error creating service catalog entry", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @PutMapping("/{serviceCode}")
    fun update(
        @PathVariable serviceCode: String,
        @Valid @RequestBody request: UpdateServiceCatalogRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ServiceCatalogResponse>> {
        val userEmail = resolveUserEmail(httpRequest)
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return try {
            val saved = service.update(serviceCode, request, userEmail, authToken)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Service catalog entry updated"))
        } catch (e: IllegalArgumentException) {
            logger.warn("Validation error updating service catalog: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: IllegalStateException) {
            logger.warn("Conflict updating service catalog: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("Error updating service catalog entry", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * Soft-delete (sets `isActive=false`). The entry is preserved so historical
     * ServiceLog rows referencing this code still resolve their label.
     */
    @DeleteMapping("/{serviceCode}")
    fun deactivate(
        @PathVariable serviceCode: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ServiceCatalogResponse>> {
        val userEmail = resolveUserEmail(httpRequest)
        return try {
            val saved = service.deactivate(serviceCode, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Service catalog entry deactivated"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: Exception) {
            logger.error("Error deactivating service catalog entry", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    /**
     * The frontend already sends `X-User-Email` on every authenticated request
     * (see createWMSAPIBuilder). The header is the authoritative actor for
     * audit purposes; falling back to "unknown" if absent rather than failing
     * lets unit/integration tests exercise the endpoint without crafting full
     * headers.
     */
    private fun resolveUserEmail(httpRequest: HttpServletRequest): String =
        httpRequest.getHeader("X-User-Email") ?: "unknown"

    private fun ServiceCatalog.toResponse() = ServiceCatalogResponse(
        serviceCode = serviceCode,
        label = label,
        unit = unit,
        standardRatePerUnit = standardRatePerUnit,
        freighaiChargeTypeId = freighaiChargeTypeId,
        vatPercent = vatPercent,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
        updatedBy = updatedBy
    )
}
