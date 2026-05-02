package com.wmspro.tenant.billing.reconciliation

import com.wmspro.common.dto.ApiResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reconciliation")
class ReconciliationController(
    private val service: ReconciliationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Per-customer reconciliation report.
     * GET /api/v1/reconciliation/report?customerId=1000036&month=2026-04
     */
    @GetMapping("/report")
    fun report(
        @RequestParam customerId: Long,
        @RequestParam month: String
    ): ResponseEntity<ApiResponse<ReconciliationReportResponse>> {
        val report = service.reportForCustomer(customerId, month)
        return ResponseEntity.ok(ApiResponse.success(report, "Reconciliation report"))
    }

    /**
     * Tenant-wide rollup for the period.
     * GET /api/v1/reconciliation/report/tenant?month=2026-04
     */
    @GetMapping("/report/tenant")
    fun reportTenant(
        @RequestParam month: String
    ): ResponseEntity<ApiResponse<ReconciliationReportResponse>> {
        val report = service.reportForTenant(month)
        return ResponseEntity.ok(ApiResponse.success(report, "Tenant reconciliation rollup"))
    }

    /**
     * CSV export for one customer + month. Excel opens this natively.
     * GET /api/v1/reconciliation/report/csv?customerId=1000036&month=2026-04
     */
    @GetMapping("/report/csv")
    fun reportCsv(
        @RequestParam customerId: Long,
        @RequestParam month: String
    ): ResponseEntity<ByteArray> {
        val csv = service.reportCsvForCustomer(customerId, month)
        val filename = "reconciliation-$customerId-$month.csv"
        val headers = HttpHeaders().apply {
            contentType = MediaType.parseMediaType("text/csv")
            set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
        }
        return ResponseEntity(csv.toByteArray(Charsets.UTF_8), headers, org.springframework.http.HttpStatus.OK)
    }
}
