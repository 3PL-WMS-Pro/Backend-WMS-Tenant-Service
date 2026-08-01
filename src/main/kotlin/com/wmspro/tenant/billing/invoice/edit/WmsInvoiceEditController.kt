package com.wmspro.tenant.billing.invoice.edit

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `/api/v1/wms-invoices/{id}/lines` — hand-editing of a generated invoice.
 *
 *   GET  /{id}/editable  → current lines + whether they can be changed
 *   PUT  /{id}/lines     → apply an edit (values and additions; no removals)
 *
 * Both forward the caller's FreighAi JWT, same as the rest of the billing
 * write surface. FreighAi's own RBAC gates these on `invoice:edit`, which is
 * the permission its `send` endpoint already requires — so any user who can
 * already issue an invoice from WMS can edit one, with no new permission to
 * provision.
 */
@RestController
@RequestMapping("/api/v1/wms-invoices")
class WmsInvoiceEditController(
    private val editService: WmsInvoiceEditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/{id}/editable")
    fun getEditable(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<InvoiceEditableView>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        if (authToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Missing Authorization header"))
        }
        return try {
            val view = editService.getEditableView(id, authToken)
            ResponseEntity.ok(ApiResponse.success(view, "Editable view retrieved"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        }
    }

    @PutMapping("/{id}/lines")
    fun editLines(
        @PathVariable id: String,
        @Valid @RequestBody request: EditInvoiceLinesRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<InvoiceEditResult>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        if (authToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Missing Authorization header"))
        }
        // Same identity source as every other billing controller in this service
        // (BillingRunController, ServiceLogController, MovementCostAdjustment…).
        // The header is client-supplied and therefore only as trustworthy as the
        // gateway in front of it; that is a service-wide property, not something
        // specific to editing, and changing it here alone would leave the audit
        // trail inconsistent with every neighbouring record.
        val userEmail = httpRequest.getHeader("X-User-Email")
            ?: httpRequest.getHeader("X-User-Id")
            ?: "unknown"

        return try {
            val result = editService.applyEdit(id, request, userEmail, authToken)
            // A failed re-send still means the edit applied, so this stays a 200
            // — but the message has to say the invoice is no longer issued,
            // otherwise an admin walks away believing the customer can see it.
            val msg = result.warning ?: buildString {
                append("Invoice updated")
                if (result.revertCycleRan) append(" and re-issued to the customer")
                if (result.zeroedLineCount > 0) {
                    append(" (${result.zeroedLineCount} zeroed line")
                    if (result.zeroedLineCount > 1) append("s")
                    append(" withheld from the invoice)")
                }
            }
            ResponseEntity.ok(ApiResponse.success(result, msg))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: InvoiceEditConflictException) {
            // Every refusal that reaches here is actionable by the admin —
            // closed period, wrong status, payment allocated, stale view.
            logger.warn("Invoice edit refused for {}: {}", id, e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Edit refused"))
        }
    }
}
