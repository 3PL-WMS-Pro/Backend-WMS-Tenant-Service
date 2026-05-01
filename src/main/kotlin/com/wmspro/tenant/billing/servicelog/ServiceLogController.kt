package com.wmspro.tenant.billing.servicelog

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * `/api/v1/service-logs` — admin / executive UI for capturing ad-hoc VAS
 * performed for a customer (palletization, crane rental, …).
 *
 * Listing endpoints are filter-shaped:
 *   - `?attachedToId=RCV-042` — used by the GRN/GIN detail screens'
 *     Service Charges panel
 *   - `?customerId=1000042&from=2026-04-01&to=2026-04-30` — used by the
 *     Customer Detail Service Logs tab
 *
 * `IllegalArgumentException` → 400 (bad input / not found), `IllegalStateException`
 * → 409 (locked / inactive). Mirrors the convention used in
 * [com.wmspro.tenant.billing.catalog.ServiceCatalogController] and
 * [com.wmspro.tenant.billing.profile.CustomerBillingProfileController].
 */
@RestController
@RequestMapping("/api/v1/service-logs")
class ServiceLogController(
    private val service: ServiceLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(
        @RequestParam(required = false) attachedToId: String?,
        @RequestParam(required = false) customerId: Long?,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?
    ): ResponseEntity<ApiResponse<List<ServiceLogResponse>>> {
        val results: List<ServiceLog> = when {
            !attachedToId.isNullOrBlank() -> service.findForGrnOrGin(attachedToId)
            customerId != null && from != null && to != null -> service.findForCustomer(customerId, from, to)
            else -> {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    ApiResponse.error("Provide either attachedToId, or (customerId + from + to)")
                )
            }
        }
        return ResponseEntity.ok(ApiResponse.success(results.map { it.toResponse() }, "Service logs retrieved"))
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: String): ResponseEntity<ApiResponse<ServiceLogResponse>> {
        val entity = service.findById(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error("ServiceLog '$id' not found")
            )
        return ResponseEntity.ok(ApiResponse.success(entity.toResponse(), "Service log retrieved"))
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateServiceLogRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ServiceLogResponse>> {
        val userEmail = resolveUserEmail(httpRequest)
        return try {
            val saved = service.create(request, userEmail)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Service log created")
            )
        } catch (e: IllegalArgumentException) {
            logger.warn("ServiceLog create validation: {}", e.message)
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: IllegalStateException) {
            logger.warn("ServiceLog create conflict: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("ServiceLog create failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateServiceLogRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<ServiceLogResponse>> {
        val userEmail = resolveUserEmail(httpRequest)
        return try {
            val saved = service.update(id, request, userEmail)
            ResponseEntity.ok(ApiResponse.success(saved.toResponse(), "Service log updated"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Validation error"))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("ServiceLog update failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        val userEmail = resolveUserEmail(httpRequest)
        return try {
            service.delete(id, userEmail)
            ResponseEntity.ok(ApiResponse.success(Unit, "Service log deleted"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: IllegalStateException) {
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Conflict"))
        } catch (e: Exception) {
            logger.error("ServiceLog delete failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Internal server error: ${e.message}")
            )
        }
    }

    private fun resolveUserEmail(httpRequest: HttpServletRequest): String =
        httpRequest.getHeader("X-User-Email") ?: "unknown"

    private fun ServiceLog.toResponse() = ServiceLogResponse(
        serviceLogId = serviceLogId,
        customerId = customerId,
        serviceCode = serviceCode,
        quantity = quantity,
        performedAt = performedAt,
        attachedTo = AttachedRefResponse(attachedTo.type, attachedTo.id, attachedTo.number),
        performedBy = performedBy,
        loggedAt = loggedAt,
        notes = notes,
        billingInvoiceId = billingInvoiceId,
        carriedOverFromMonth = carriedOverFromMonth,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        isLocked = billingInvoiceId != null
    )
}
