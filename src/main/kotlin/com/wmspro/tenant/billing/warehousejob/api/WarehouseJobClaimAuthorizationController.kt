package com.wmspro.tenant.billing.warehousejob.api

import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.dto.ApiResponse
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.OrphanClaimDecisionResponse
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentState
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimOrphanDecisionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/internal/billing-invoices")
class WarehouseJobClaimAuthorizationController(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val intentRepository: WarehouseJobClaimIntentRepository,
    private val orphanDecisionService: WarehouseJobClaimOrphanDecisionService
) {
    @GetMapping("/{billingInvoiceId}/claim-authorization")
    fun authorize(
        @PathVariable billingInvoiceId: String,
        @RequestParam payloadVersion: Long
    ): ResponseEntity<ApiResponse<ClaimAuthorizationResponse>> {
        val invoice = invoiceRepository.findById(billingInvoiceId).orElse(null)
        val intent = intentRepository.findById(billingInvoiceId).orElse(null)
        val authorized = (invoice?.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
            invoice.warehouseJobPayloadVersion == payloadVersion) ||
            (intent?.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                intent.payloadVersion == payloadVersion && intent.state == WarehouseJobClaimIntentState.CLAIMING &&
                intent.expiresAt.isAfter(java.time.Instant.now()))
        if (!authorized) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("CUTOVER_CONFLICT: billing invoice is not the requested V1 owner"))
        }
        return ResponseEntity.ok(
            ApiResponse.success(
                ClaimAuthorizationResponse(
                    billingInvoiceId = billingInvoiceId,
                    generationContractVersion = WarehouseJobGenerationContracts.V1,
                    payloadVersion = payloadVersion
                )
            )
        )
    }

    @GetMapping("/{billingInvoiceId}/claim-orphan-decision")
    fun orphanDecision(@PathVariable billingInvoiceId: String): ResponseEntity<ApiResponse<OrphanClaimDecisionResponse>> =
        ResponseEntity.ok(ApiResponse.success(orphanDecisionService.decide(billingInvoiceId)))
}

data class ClaimAuthorizationResponse(
    val billingInvoiceId: String,
    val generationContractVersion: String?,
    val payloadVersion: Long
)
