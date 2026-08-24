package com.wmspro.tenant.billing.warehousejob.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.dto.ApiResponse
import com.wmspro.common.external.freighai.client.FreighAiWarehouseJobClient
import com.wmspro.common.external.freighai.client.WarehouseJobDocumentResult
import com.wmspro.common.external.freighai.dto.CreateFreighAiWarehouseJobRequest
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshotRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.*
import com.wmspro.tenant.repository.UserRoleMappingRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

data class WarehouseJobWmsPage(
    val content: List<Map<String, Any?>>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int
)

@Service
class WarehouseJobStaffAuthorization(private val users: UserRoleMappingRepository) {
    fun require(request: HttpServletRequest, permission: String) {
        val email = request.getHeader("X-User-Email")?.trim()?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user identity is required")
        val allowed = users.findByEmail(email).any { it.isActive && it.hasPermission(permission) }
        if (!allowed) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Warehouse Job permission '$permission' is required")
    }
}

@Service
class WarehouseJobStaffProjectionService(
    private val mongoTemplate: MongoTemplate,
    private val invoices: WmsBillingInvoiceRepository,
    private val snapshots: BillingRunCostSnapshotRepository,
    private val outbox: WarehouseJobOutboxRepository,
    private val recovery: WarehouseJobRecoveryService,
    private val warehouseClient: FreighAiWarehouseJobClient,
    private val objectMapper: ObjectMapper
) {
    fun list(page: Int, size: Int, customerId: Long?, billingMonth: String?, projectCode: String?, lifecycle: String?): WarehouseJobWmsPage {
        val safePage = page.coerceAtLeast(0)
        val safeSize = size.coerceIn(1, 100)
        val criteria = Criteria.where("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
        customerId?.let { criteria.and("customerId").`is`(it) }
        billingMonth?.takeIf(String::isNotBlank)?.let { criteria.and("billingMonth").`is`(it) }
        projectCode?.takeIf(String::isNotBlank)?.let { criteria.and("projectCode").`is`(it) }
        lifecycle?.takeIf(String::isNotBlank)?.let { criteria.and("warehouseJobStatus").`is`(it) }
        val query = Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "generatedAt"))
            .skip(safePage.toLong() * safeSize).limit(safeSize)
        val rows = mongoTemplate.find(query, WmsBillingInvoice::class.java).map(::summary)
        val total = mongoTemplate.count(Query.query(criteria), WmsBillingInvoice::class.java)
        return WarehouseJobWmsPage(rows, safePage, safeSize, total, if (total == 0L) 0 else ((total + safeSize - 1) / safeSize).toInt())
    }

    fun detail(id: String, authToken: String): Map<String, Any?> {
        val invoice = recovery.resolve(id)
        requireV1(invoice)
        val remote = invoice.warehouseJobId?.let { jobId ->
            when (val result = warehouseClient.get(jobId, authToken)) {
                is WarehouseJobDocumentResult.Found -> objectMapper.convertValue(result.document, Map::class.java) as Map<String, Any?>
                else -> null
            }
        } ?: frozenJobProjection(invoice)
        return mapOf(
            "warehouseJob" to remote,
            "wms" to summary(invoice),
            "sources" to snapshots.findByBillingInvoiceId(invoice.billingInvoiceId).filter {
                it.generationContractVersion == WarehouseJobGenerationContracts.V1
            },
            "outbox" to outbox.findByGenerationContractVersionAndBillingInvoiceId(
                WarehouseJobGenerationContracts.V1, invoice.billingInvoiceId
            ).sortedBy { it.createdAt }
        )
    }

    fun sources(id: String) = snapshots.findByBillingInvoiceId(recovery.resolve(id).also(::requireV1).billingInvoiceId)
        .filter { it.generationContractVersion == WarehouseJobGenerationContracts.V1 }

    fun reconciliation(id: String, authToken: String): JsonNode {
        val invoice = recovery.resolve(id)
        requireV1(invoice)
        val jobId = invoice.warehouseJobId ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Warehouse Job is not synchronized yet")
        return when (val result = warehouseClient.getReconciliation(jobId, authToken)) {
            is WarehouseJobDocumentResult.Found -> result.document
            WarehouseJobDocumentResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Warehouse Job not found in FreighAI")
            is WarehouseJobDocumentResult.Unavailable -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.errorMessage)
        }
    }

    private fun frozenJobProjection(invoice: WmsBillingInvoice): Map<String, Any?>? {
        val command = outbox.findByGenerationContractVersionAndBillingInvoiceId(
            WarehouseJobGenerationContracts.V1, invoice.billingInvoiceId
        ).firstOrNull { it.operation == WarehouseJobOutboxOperation.UPSERT_WJ } ?: return null
        return try {
            val payload = objectMapper.readValue(command.payload, CreateFreighAiWarehouseJobRequest::class.java)
            mapOf(
                "warehouseJobId" to invoice.warehouseJobId,
                "jobId" to invoice.warehouseJobId,
                "warehouseJobNo" to invoice.warehouseJobNumber,
                "jobNo" to invoice.warehouseJobNumber,
                "warehouseLifecycle" to invoice.warehouseJobStatus,
                "lifecycle" to invoice.warehouseJobStatus,
                "customerId" to payload.customerSnapshot.customerId,
                "customerName" to payload.customerSnapshot.customerName,
                "customerSnapshot" to payload.customerSnapshot,
                "warehouseContext" to payload.warehouseContext,
                "commercialSnapshot" to payload.commercialSnapshot,
                "sourceContentHash" to payload.sourceContentHash,
                "generationContractVersion" to payload.generationContractVersion
            )
        } catch (_: Exception) { null }
    }

    private fun summary(invoice: WmsBillingInvoice): Map<String, Any?> = mapOf(
        "billingInvoiceId" to invoice.billingInvoiceId,
        "warehouseJobId" to (invoice.warehouseJobId ?: invoice.billingInvoiceId),
        "jobId" to invoice.warehouseJobId,
        "warehouseJobNo" to invoice.warehouseJobNumber,
        "jobNo" to invoice.warehouseJobNumber,
        "customerId" to invoice.customerId,
        "customerName" to invoice.warehouseJobCustomerName,
        "projectCode" to invoice.projectCode,
        "billingMonth" to invoice.billingMonth,
        "sellingTotal" to invoice.grandTotal,
        "grandTotal" to invoice.grandTotal,
        "currencyCode" to invoice.warehouseJobCurrencyCode,
        "warehouseLifecycle" to invoice.warehouseJobStatus,
        "lifecycle" to invoice.warehouseJobStatus,
        "warehouseJobSyncStatus" to invoice.warehouseJobSyncState,
        "syncStatus" to invoice.warehouseJobSyncState,
        "generationContractVersion" to invoice.generationContractVersion,
        "warehouseJobPayloadVersion" to invoice.warehouseJobPayloadVersion,
        "warehouseJobPayloadHash" to invoice.warehouseJobPayloadHash,
        "lastWarehouseJobSyncedAt" to invoice.warehouseJobLastSyncedAt,
        "lastWarehouseJobSyncError" to invoice.warehouseJobLastError,
        "freighaiInvoiceId" to invoice.freighaiInvoiceId,
        "freighaiInvoiceNo" to invoice.freighaiInvoiceNo,
        "freighaiStatus" to invoice.freighaiStatus
    )

    private fun requireV1(invoice: WmsBillingInvoice) {
        if (invoice.generationContractVersion != WarehouseJobGenerationContracts.V1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "CUTOVER_CONFLICT: record is not WAREHOUSE_JOB_V1")
        }
    }

}

@RestController
@RequestMapping("/api/v1/warehouse-jobs")
class WarehouseJobStaffController(
    private val projection: WarehouseJobStaffProjectionService,
    private val recovery: WarehouseJobRecoveryService,
    private val outboxWorker: WarehouseJobOutboxWorker,
    private val authorization: WarehouseJobStaffAuthorization,
    private val cancellation: WarehouseJobCancellationService
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) customerId: Long?,
        @RequestParam(required = false) billingMonth: String?,
        @RequestParam(required = false) projectCode: String?,
        @RequestParam(required = false) lifecycle: String?,
        request: HttpServletRequest
    ): ResponseEntity<ApiResponse<WarehouseJobWmsPage>> {
        authorization.require(request, "canViewWarehouseJobs")
        return ResponseEntity.ok(ApiResponse.success(projection.list(page, size, customerId, billingMonth, projectCode, lifecycle)))
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        authorization.require(request, "canViewWarehouseJobs")
        return ResponseEntity.ok(ApiResponse.success(projection.detail(id, request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty())))
    }

    @GetMapping("/{id}/sources")
    fun sources(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ApiResponse<Any>> {
        authorization.require(request, "canViewWarehouseJobs")
        return ResponseEntity.ok(ApiResponse.success(projection.sources(id)))
    }

    @GetMapping("/{id}/reconciliation")
    fun reconciliation(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ApiResponse<JsonNode>> {
        authorization.require(request, "canViewWarehouseJobs")
        return ResponseEntity.ok(ApiResponse.success(projection.reconciliation(id, request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty())))
    }

    @PostMapping("/{id}/retry", "/{id}/reconcile")
    fun retry(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        authorization.require(request, "canSyncWarehouseJobs")
        val authToken = request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        val invoice = recovery.retry(id, authToken)
        outboxWorker.drainInvoice(
            workerId = "wms-staff-${java.util.UUID.randomUUID()}",
            billingInvoiceId = invoice.billingInvoiceId,
            authToken = authToken
        )
        val refreshed = recovery.resolve(invoice.billingInvoiceId)
        return ResponseEntity.accepted().body(ApiResponse.success(mapOf(
            "billingInvoiceId" to refreshed.billingInvoiceId,
            "syncStatus" to refreshed.warehouseJobSyncState
        ), "Warehouse Job recovery attempted"))
    }

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: String,
        @RequestBody body: Map<String, String>,
        request: HttpServletRequest
    ): ResponseEntity<ApiResponse<Map<String, Any?>>> {
        authorization.require(request, "canCancelWarehouseJobs")
        val invoice = cancellation.cancel(
            id,
            body["reason"].orEmpty(),
            request.getHeader("X-User-Email") ?: "unknown",
            request.getHeader(HttpHeaders.AUTHORIZATION).orEmpty()
        )
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "billingInvoiceId" to invoice.billingInvoiceId,
            "warehouseLifecycle" to invoice.warehouseJobStatus
        ), "Warehouse Job cancelled; source claims remain committed"))
    }
}
