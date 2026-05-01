package com.wmspro.tenant.billing.invoice

import com.wmspro.common.dto.ApiResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant

/**
 * `/api/v1/billing-runs` — admin-facing API to drive the billing engine.
 *
 *   POST /preview        → BillingPreviewResponse  (no DB writes)
 *   POST /generate       → WmsBillingInvoiceResponse (full submission)
 *   POST /generate-all   → GenerateAllBillingRunsResponse  (sweep all enabled customers)
 *   POST /cancel/{id}    → WmsBillingInvoiceResponse (cancel + cascade unlock)
 *
 * The cron in Phase 10 hits `/generate-all` once a month with `billingMonth =
 * previous month`. Same endpoint also serves manual admin sweeps.
 */
@RestController
@RequestMapping("/api/v1/billing-runs")
class BillingRunController(
    private val service: BillingRunService,
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val billingProfileRepository: com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository,
    private val summaryRepository: BillingRunSummaryRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Phase 10 — admin dashboard pulls run history for the BillingRunsScreen. */
    @GetMapping("/summaries")
    fun listSummaries(
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false, defaultValue = "20") size: Int
    ): ResponseEntity<ApiResponse<List<BillingRunSummary>>> {
        val pageable = org.springframework.data.domain.PageRequest.of(page, size)
        val summaries = summaryRepository.findAllByOrderByTriggeredAtDesc(pageable)
        return ResponseEntity.ok(ApiResponse.success(summaries.content, "Summaries retrieved"))
    }

    @PostMapping("/preview")
    fun preview(
        @Valid @RequestBody request: BillingPreviewRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<BillingPreviewResponse>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        return try {
            val preview = service.preview(request.customerId, request.billingMonth, authToken)
            ResponseEntity.ok(ApiResponse.success(preview, "Preview ready"))
        } catch (e: Exception) {
            logger.error("preview failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Preview failed: ${e.message}")
            )
        }
    }

    @PostMapping("/generate")
    fun generate(
        @Valid @RequestBody request: GenerateBillingRunRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<WmsBillingInvoiceResponse>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val saved = service.generate(request.customerId, request.billingMonth, userEmail, authToken)
            ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.success(saved.toResponse(), "Billing run submitted")
            )
        } catch (e: IllegalStateException) {
            logger.warn("generate refused: {}", e.message)
            ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.message ?: "Cannot generate"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.message ?: "Bad request"))
        } catch (e: Exception) {
            logger.error("generate failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Generate failed: ${e.message}")
            )
        }
    }

    /**
     * Sweep — runs `generate` for every billingEnabled customer. Failures
     * for individual customers don't abort the whole sweep; the response
     * lists succeeded / skipped / failed buckets for admin review.
     */
    @PostMapping("/generate-all")
    fun generateAll(
        @Valid @RequestBody request: GenerateAllBillingRunsRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<GenerateAllBillingRunsResponse>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "CRON"

        val customerIds = request.customerIds
            ?: billingProfileRepository.findByBillingEnabled(true).map { it.customerId }

        val succeeded = mutableListOf<Long>()
        val skipped = mutableListOf<SkipDetail>()
        val failed = mutableListOf<FailDetail>()

        for (cid in customerIds) {
            try {
                service.generate(cid, request.billingMonth, userEmail, authToken)
                succeeded += cid
            } catch (e: IllegalStateException) {
                // Already generated, no billable activity, etc. — not a failure.
                skipped += SkipDetail(cid, e.message ?: "skipped")
            } catch (e: Exception) {
                logger.error("generate-all failed for customerId={}", cid, e)
                failed += FailDetail(cid, e.message ?: "unexpected error")
            }
        }

        return ResponseEntity.ok(
            ApiResponse.success(
                GenerateAllBillingRunsResponse(
                    billingMonth = request.billingMonth,
                    triggeredAt = Instant.now(),
                    triggeredBy = userEmail,
                    succeeded = succeeded,
                    skipped = skipped,
                    failed = failed
                ),
                "Sweep complete: ${succeeded.size} succeeded, ${skipped.size} skipped, ${failed.size} failed"
            )
        )
    }

    @PostMapping("/cancel/{billingInvoiceId}")
    fun cancel(
        @PathVariable billingInvoiceId: String,
        @Valid @RequestBody request: CancelBillingInvoiceRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ApiResponse<WmsBillingInvoiceResponse>> {
        val authToken = httpRequest.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val userEmail = httpRequest.getHeader("X-User-Email") ?: "unknown"
        return try {
            val cancelled = service.cancel(billingInvoiceId, request.reason, userEmail, authToken)
            ResponseEntity.ok(ApiResponse.success(cancelled.toResponse(), "Billing run cancelled"))
        } catch (e: IllegalArgumentException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.message ?: "Not found"))
        } catch (e: Exception) {
            logger.error("cancel failed", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                ApiResponse.error("Cancel failed: ${e.message}")
            )
        }
    }
}

internal fun WmsBillingInvoice.toResponse() = WmsBillingInvoiceResponse(
    billingInvoiceId = billingInvoiceId,
    customerId = customerId,
    billingMonth = billingMonth,
    status = status,
    storageLines = storageLines,
    movementLines = movementLines,
    serviceLines = serviceLines,
    subtotal = subtotal,
    totalVat = totalVat,
    grandTotal = grandTotal,
    minimumChargeApplied = minimumChargeApplied,
    freighaiInvoiceId = freighaiInvoiceId,
    freighaiInvoiceNo = freighaiInvoiceNo,
    freighaiVoucherId = freighaiVoucherId,
    freighaiReferenceNo = freighaiReferenceNo,
    freighaiStatus = freighaiStatus,
    freighaiInvoiceDate = freighaiInvoiceDate,
    freighaiDueDate = freighaiDueDate,
    freighaiOutstandingAmount = freighaiOutstandingAmount,
    lastSyncedAt = lastSyncedAt,
    submissionAttempts = submissionAttempts,
    generatedAt = generatedAt,
    generatedBy = generatedBy,
    cancelledAt = cancelledAt,
    cancelledBy = cancelledBy,
    cancelReason = cancelReason
)
