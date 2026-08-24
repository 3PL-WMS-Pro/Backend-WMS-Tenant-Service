package com.wmspro.tenant.billing.servicelog

import com.wmspro.common.billing.ReserveBillingSourceClaimRequest
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.dto.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/internal/service-logs")
class ServiceLogBillingClaimInternalController(
    private val service: ServiceLogBillingClaimService
) {
    @PostMapping("/{id}/billing-claim")
    fun reserve(@PathVariable id: String, @RequestBody request: ReserveBillingSourceClaimRequest) = respond {
        service.reserve(id, request)
    }

    @PostMapping("/{id}/billing-claim/commit")
    fun commit(@PathVariable id: String, @RequestBody request: TransitionBillingSourceClaimRequest) = respond {
        service.commit(id, request)
    }

    @PostMapping("/{id}/billing-claim/release")
    fun release(@PathVariable id: String, @RequestBody request: TransitionBillingSourceClaimRequest) = respond {
        service.release(id, request)
    }

    private fun respond(action: () -> ServiceLog): ResponseEntity<ApiResponse<ServiceLog>> = try {
        ResponseEntity.ok(ApiResponse.success(action()))
    } catch (e: IllegalArgumentException) {
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
    } catch (e: BillingClaimConflictException) {
        ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "CUTOVER_CONFLICT"))
    }
}
