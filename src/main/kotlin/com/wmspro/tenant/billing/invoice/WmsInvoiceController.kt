package com.wmspro.tenant.billing.invoice

import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.external.freighai.client.FreighAiInvoiceClient
import com.wmspro.common.external.freighai.client.SendResult
import com.wmspro.common.external.freighai.dto.FreighAiInvoiceResponse
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

/**
 * `/api/v1/wms-invoices` — read surface for the WMS billing module.
 *
 *   GET /                → paginated list with filters
 *   GET /{id}            → detail merge: WmsBillingInvoice + live FreighAi data
 *   POST /{id}/sync      → force-refresh cached FreighAi state
 *
 * Phase 8 will add the write actions (send / email / mark-paid / cancel /
 * PDF stream). This controller is read-only.
 */
@RestController
@RequestMapping("/api/v1/wms-invoices")
class WmsInvoiceController(
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val freighAiInvoiceClient: FreighAiInvoiceClient,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Filterable paginated list. All filters are optional; missing filters =
     * no constraint on that axis.
     *
     * Sort: most recently generated first.
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) customerId: Long?,
        @RequestParam(required = false) billingMonth: String?,
        @RequestParam(required = false) status: BillingInvoiceStatus?,
        @RequestParam(required = false) freighaiStatus: String?,
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int
    ): ResponseEntity<ApiResponse<WmsInvoiceListResponse>> {
        val criteria = Criteria()
        val parts = mutableListOf<Criteria>()
        customerId?.let { parts += Criteria.where("customerId").`is`(it) }
        billingMonth?.let { parts += Criteria.where("billingMonth").`is`(it) }
        status?.let { parts += Criteria.where("status").`is`(it) }
        freighaiStatus?.let { parts += Criteria.where("freighaiStatus").`is`(it) }
        if (parts.isNotEmpty()) criteria.andOperator(*parts.toTypedArray())

        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "generatedAt"))
        val query = Query(criteria).with(pageable)

        val total = mongoTemplate.count(Query(criteria), WmsBillingInvoice::class.java)
        val rows = mongoTemplate.find(query, WmsBillingInvoice::class.java)

        val response = WmsInvoiceListResponse(
            page = page,
            size = size,
            totalElements = total,
            totalPages = if (size == 0) 0 else ((total + size - 1) / size).toInt(),
            content = rows.map { it.toResponse() }
        )
        return ResponseEntity.ok(ApiResponse.success(response, "Invoices retrieved"))
    }

    /**
     * Detail = local breakdown + live FreighAi fetch (lazy refresh of cached
     * status fields). Returns the FreighAi invoice payload alongside our
     * WmsBillingInvoice so the frontend can render both in one shot.
     *
     * If FreighAi is unreachable, returns the local data with a `freighaiData`
     * = null and `freighaiFetchError` populated. Frontend shows a banner
     * explaining the live data is stale.
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<WmsInvoiceDetailResponse>> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error("WmsBillingInvoice '$id' not found")
            )

        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()

        var freighaiData: FreighAiInvoiceResponse? = null
        var freighaiFetchError: String? = null
        if (invoice.freighaiInvoiceId != null && authToken.isNotBlank()) {
            try {
                freighaiData = freighAiInvoiceClient.getInvoice(invoice.freighaiInvoiceId, authToken)
                if (freighaiData == null) {
                    freighaiFetchError = "FreighAi returned no data for invoice ${invoice.freighaiInvoiceId}"
                }
            } catch (e: Exception) {
                freighaiFetchError = "FreighAi unreachable: ${e.message}"
            }
        }

        // Lazy cache refresh — write only when something actually changed (audit
        // fix, Finding 8). Avoids a Mongo write on every detail load when the
        // FreighAi status / dates / outstanding amount hasn't moved. lastSyncedAt
        // does NOT update by itself — leaving it stale lets the hourly sync cron
        // pick the row up next time, which is the right freshness contract.
        val updatedInvoice = freighaiData?.let { f ->
            val changed = invoice.freighaiStatus != f.currentStatus
                || invoice.freighaiInvoiceDate != f.invoiceDate
                || invoice.freighaiDueDate != f.dueDate
                || invoice.freighaiOutstandingAmount != f.outstandingAmount
            if (changed) {
                invoiceRepository.save(
                    invoice.copy(
                        freighaiStatus = f.currentStatus,
                        freighaiInvoiceDate = f.invoiceDate,
                        freighaiDueDate = f.dueDate,
                        freighaiOutstandingAmount = f.outstandingAmount,
                        lastSyncedAt = Instant.now()
                    )
                )
            } else {
                invoice
            }
        } ?: invoice

        return ResponseEntity.ok(
            ApiResponse.success(
                WmsInvoiceDetailResponse(
                    wms = updatedInvoice.toResponse(),
                    freighai = freighaiData,
                    freighaiFetchError = freighaiFetchError
                ),
                "Invoice detail retrieved"
            )
        )
    }

    /**
     * Force-refresh the cached FreighAi status fields without returning the
     * full detail (used by the list view's manual "sync" button when we
     * want to update the row without navigating).
     */
    @PostMapping("/{id}/sync")
    fun sync(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<WmsBillingInvoiceResponse>> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                ApiResponse.error("WmsBillingInvoice '$id' not found")
            )
        if (invoice.freighaiInvoiceId == null) {
            return ResponseEntity.ok(
                ApiResponse.success(invoice.toResponse(), "No FreighAi binding to sync")
            )
        }
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val freighai = freighAiInvoiceClient.getInvoice(invoice.freighaiInvoiceId, authToken)
        if (freighai == null) {
            return ResponseEntity.ok(
                ApiResponse.success(invoice.toResponse(), "FreighAi unreachable; returning cached")
            )
        }
        val updated = invoiceRepository.save(
            invoice.copy(
                freighaiStatus = freighai.currentStatus,
                freighaiInvoiceDate = freighai.invoiceDate,
                freighaiDueDate = freighai.dueDate,
                freighaiOutstandingAmount = freighai.outstandingAmount,
                lastSyncedAt = Instant.now()
            )
        )
        return ResponseEntity.ok(ApiResponse.success(updated.toResponse(), "Sync complete"))
    }

    // ──────────────────────────────────────────────────────────────────
    // Phase 8 write surface — FreighAi proxies
    // ──────────────────────────────────────────────────────────────────

    @PostMapping("/{id}/send")
    fun send(
        @PathVariable id: String,
        @RequestBody(required = false) body: Map<String, String>?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<WmsBillingInvoiceResponse>> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Invoice '$id' not found"))
        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("No FreighAi binding to send"))
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return when (val result = freighAiInvoiceClient.sendInvoice(freighaiInvoiceId, body?.get("remarks"), authToken)) {
            is SendResult.Success -> {
                val freighai = freighAiInvoiceClient.getInvoice(freighaiInvoiceId, authToken)
                val updated = freighai?.let {
                    invoiceRepository.save(
                        invoice.copy(
                            freighaiStatus = it.currentStatus,
                            freighaiInvoiceDate = it.invoiceDate,
                            freighaiDueDate = it.dueDate,
                            lastSyncedAt = Instant.now()
                        )
                    )
                } ?: invoice
                ResponseEntity.ok(ApiResponse.success(updated.toResponse(), "Invoice sent"))
            }
            is SendResult.Failure -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(
                ApiResponse.error("FreighAi send failed: ${result.errorMessage}")
            )
        }
    }

    @GetMapping("/{id}/email-draft")
    fun emailDraft(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<String> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Invoice not found")
        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).body("No FreighAi binding")
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val draft = freighAiInvoiceClient.getEmailDraft(freighaiInvoiceId, authToken)
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("FreighAi returned no draft")
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(draft)
    }

    @PostMapping("/{id}/email")
    fun email(
        @PathVariable id: String,
        @RequestBody body: Map<String, Any?>,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<Unit>> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Invoice not found"))
        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("No FreighAi binding"))
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val ok = freighAiInvoiceClient.sendEmail(freighaiInvoiceId, body, authToken)
        return if (ok) ResponseEntity.ok(ApiResponse.success(Unit, "Email sent"))
        else ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error("FreighAi email send failed"))
    }

    @GetMapping("/{id}/pdf")
    fun pdf(
        @PathVariable id: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ByteArray> {
        val invoice = invoiceRepository.findById(id).orElse(null)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val freighaiInvoiceId = invoice.freighaiInvoiceId
            ?: return ResponseEntity.status(HttpStatus.CONFLICT).build()
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val bytes = freighAiInvoiceClient.getInvoicePdf(freighaiInvoiceId, authToken)
            ?: return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        val filename = "${invoice.freighaiInvoiceNo ?: invoice.billingInvoiceId}.pdf"
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"$filename\"")
            .body(bytes)
    }
}

data class WmsInvoiceListResponse(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val content: List<WmsBillingInvoiceResponse>
)

/**
 * Detail response — combines our local snapshot with a live snapshot from
 * FreighAi. Frontend renders WMS breakdown from `wms` and FreighAi metadata
 * (e.g., status, dueDate, outstanding) from `freighai`.
 */
data class WmsInvoiceDetailResponse(
    val wms: WmsBillingInvoiceResponse,
    val freighai: FreighAiInvoiceResponse?,
    val freighaiFetchError: String?
)
