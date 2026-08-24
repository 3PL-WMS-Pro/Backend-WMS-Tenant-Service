package com.wmspro.tenant.billing.warehousejob.orchestration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.common.external.freighai.client.*
import com.wmspro.common.external.freighai.dto.*
import com.wmspro.tenant.billing.invoice.BillingInvoiceStatus
import com.wmspro.tenant.billing.invoice.WarehouseJobSyncState
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.invoice.MovementDirection
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.security.MessageDigest

@Service
class WarehouseJobBindingService(
    private val mongoTemplate: MongoTemplate,
    private val invoiceRepository: WmsBillingInvoiceRepository
) {
    fun load(command: WarehouseJobOutbox): WmsBillingInvoice? =
        invoiceRepository.findById(command.billingInvoiceId).orElse(null)?.takeIf {
            it.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                it.warehouseJobPayloadVersion == command.payloadVersion &&
                (command.operation != WarehouseJobOutboxOperation.UPSERT_WJ || it.warehouseJobPayloadHash == command.payloadHash)
        }

    fun bindWarehouseJob(command: WarehouseJobOutbox, job: FreighAiWarehouseJobResponse): Boolean =
        update(command, Criteria.where("warehouseJobPayloadHash").`is`(command.payloadHash), Update()
            .set("warehouseJobId", job.jobId)
            .set("warehouseJobNumber", job.jobNo)
            .set("warehouseJobStatus", job.lifecycle)
            .set("warehouseJobSyncState", WarehouseJobSyncState.PENDING)
            .set("warehouseJobLastSyncedAt", Instant.now())
            .unset("warehouseJobLastError"))

    fun bindSalesInvoice(command: WarehouseJobOutbox, invoice: FreighAiInvoiceResponse): Boolean =
        update(command, null, Update()
            .set("status", BillingInvoiceStatus.SUBMITTED)
            .set("freighaiInvoiceId", invoice.invoiceId)
            .set("freighaiInvoiceNo", invoice.invoiceNo)
            .set("freighaiVoucherId", invoice.voucherId)
            .set("freighaiStatus", invoice.currentStatus ?: "DRAFT")
            .set("freighaiInvoiceDate", invoice.invoiceDate)
            .set("freighaiDueDate", invoice.dueDate)
            .set("freighaiOutstandingAmount", invoice.outstandingAmount)
            .set("lastSyncedAt", Instant.now())
            .set("warehouseJobSyncState", WarehouseJobSyncState.SYNCED)
            .set("warehouseJobLastSyncedAt", Instant.now())
            .unset("warehouseJobLastError"))

    fun failure(command: WarehouseJobOutbox, error: String, manual: Boolean) {
        update(command, null, Update()
            .set("warehouseJobSyncState", if (manual) WarehouseJobSyncState.MANUAL_REVIEW else WarehouseJobSyncState.FAILED)
            .set("warehouseJobLastError", error.take(500))
            .set("warehouseJobLastSyncedAt", Instant.now()))
    }

    private fun update(command: WarehouseJobOutbox, extra: Criteria?, update: Update): Boolean {
        val criteria = Criteria.where("billingInvoiceId").`is`(command.billingInvoiceId)
            .and("generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
            .and("warehouseJobPayloadVersion").`is`(command.payloadVersion)
        val query = if (extra == null) Query.query(criteria) else Query.query(Criteria().andOperator(criteria, extra))
        return mongoTemplate.updateFirst(query, update, WmsBillingInvoice::class.java).matchedCount == 1L
    }
}

@Component
class WarehouseJobUpsertOutboxHandler(
    private val objectMapper: ObjectMapper,
    private val client: FreighAiWarehouseJobClient,
    private val binding: WarehouseJobBindingService
) : WarehouseJobOutboxCommandHandler {
    override fun supports(operation: WarehouseJobOutboxOperation) = operation == WarehouseJobOutboxOperation.UPSERT_WJ

    override fun handle(command: WarehouseJobOutbox, authToken: String): OutboxCommandResult {
        if (authToken.isBlank()) return retry(command, "FreighAI authorization token is not available")
        val invoice = binding.load(command) ?: return manual(command, "V1 invoice/payload revision no longer matches command")
        val request = try { objectMapper.readValue(command.payload, CreateFreighAiWarehouseJobRequest::class.java) }
        catch (e: Exception) { return manual(command, "Stored Warehouse Job payload is invalid: ${e.message}") }
        return when (val exact = client.findByExternalReference(
            "WMS", request.externalReference, authToken, request.warehouseContext.sourceTenantId
        )) {
            is WarehouseJobLookupResult.Found -> adoptOrReplace(command, request, exact.job, authToken)
            WarehouseJobLookupResult.NotFound -> when (val created = client.create(
                request, command.idempotencyKey, authToken
            )) {
                is WarehouseJobMutationResult.Success -> adopt(command, request, created.job)
                is WarehouseJobMutationResult.Indeterminate -> recover(command, request, authToken)
                is WarehouseJobMutationResult.Rejected -> recoverRejected(command, request, authToken, created.errorMessage)
            }
            is WarehouseJobLookupResult.Unavailable -> retry(command, exact.errorMessage)
        }
    }

    private fun adoptOrReplace(
        command: WarehouseJobOutbox,
        request: CreateFreighAiWarehouseJobRequest,
        existing: FreighAiWarehouseJobResponse,
        authToken: String
    ): OutboxCommandResult {
        if (existing.sourceContentHash == request.sourceContentHash) return adopt(command, request, existing)
        val existingVersion = existing.version
        if (existing.lifecycle != "ACTIVE" || existingVersion == null) {
            return manual(command, "Warehouse Job identity exists with different content and is not safely replaceable")
        }
        val replacement = ReplaceFreighAiWarehouseSnapshotRequest(
            expectedVersion = existingVersion,
            sourceRevision = request.sourceRevision,
            sourceContentHash = request.sourceContentHash,
            commercialSnapshot = request.commercialSnapshot
        )
        return when (val result = client.replaceSnapshot(
            existing.jobId, replacement, existingVersion, command.idempotencyKey + "-replace",
            authToken
        )) {
            is WarehouseJobMutationResult.Success -> adopt(command, request, result.job)
            is WarehouseJobMutationResult.Indeterminate -> recover(command, request, authToken)
            is WarehouseJobMutationResult.Rejected -> recoverRejected(command, request, authToken, result.errorMessage)
        }
    }

    private fun recover(command: WarehouseJobOutbox, request: CreateFreighAiWarehouseJobRequest, authToken: String) =
        when (val exact = client.findByExternalReference(
            "WMS", request.externalReference, authToken, request.warehouseContext.sourceTenantId
        )) {
            is WarehouseJobLookupResult.Found -> adoptOrReplace(command, request, exact.job, authToken)
            WarehouseJobLookupResult.NotFound -> retry(command, "Remote mutation outcome is not yet visible")
            is WarehouseJobLookupResult.Unavailable -> retry(command, exact.errorMessage)
        }

    private fun recoverRejected(command: WarehouseJobOutbox, request: CreateFreighAiWarehouseJobRequest, authToken: String, error: String) =
        when (val exact = client.findByExternalReference(
            "WMS", request.externalReference, authToken, request.warehouseContext.sourceTenantId
        )) {
            is WarehouseJobLookupResult.Found -> adoptOrReplace(command, request, exact.job, authToken)
            WarehouseJobLookupResult.NotFound -> manual(command, error)
            is WarehouseJobLookupResult.Unavailable -> retry(command, "Rejected response followed by unavailable recovery read: ${exact.errorMessage}")
        }

    private fun adopt(command: WarehouseJobOutbox, request: CreateFreighAiWarehouseJobRequest, job: FreighAiWarehouseJobResponse): OutboxCommandResult {
        if (job.sourceContentHash != request.sourceContentHash) return manual(command, "Remote Warehouse Job hash does not match frozen payload")
        return if (binding.bindWarehouseJob(command, job)) OutboxCommandResult.Success(
            remoteEntityId = job.jobId,
            remoteResponseHash = remoteHash(
                job.jobId, job.jobNo, job.lifecycle, job.externalReference,
                job.sourceContentHash, job.version?.toString()
            )
        )
        else manual(command, "Local Warehouse Job binding lost its payload fence")
    }

    private fun retry(c: WarehouseJobOutbox, e: String) = OutboxCommandResult.Retry(e).also { binding.failure(c, e, false) }
    private fun manual(c: WarehouseJobOutbox, e: String) = OutboxCommandResult.ManualReview(e).also { binding.failure(c, e, true) }
}

@Component
class WarehouseSalesInvoiceOutboxHandler(
    private val objectMapper: ObjectMapper,
    private val outboxRepository: WarehouseJobOutboxRepository,
    private val invoiceClient: FreighAiInvoiceClient,
    private val binding: WarehouseJobBindingService
) : WarehouseJobOutboxCommandHandler {
    @org.springframework.beans.factory.annotation.Value("\${app.external-api.freighai.aed-currency-id:CUR-AED}")
    private lateinit var currencyId: String

    override fun supports(operation: WarehouseJobOutboxOperation) = operation == WarehouseJobOutboxOperation.CREATE_OR_LINK_SI

    override fun handle(command: WarehouseJobOutbox, authToken: String): OutboxCommandResult {
        if (authToken.isBlank()) return retry(command, "FreighAI authorization token is not available")
        val local = binding.load(command) ?: return manual(command, "V1 invoice/payload revision no longer matches command")
        val jobId = local.warehouseJobId ?: return retry(command, "Warehouse Job binding is not available")
        val jobNo = local.warehouseJobNumber ?: return retry(command, "Warehouse Job number is not available")
        val predecessor = command.dependencyOutboxId?.let { outboxRepository.findById(it).orElse(null) }
            ?: return manual(command, "Warehouse Job predecessor payload is missing")
        val wj = try { objectMapper.readValue(predecessor.payload, CreateFreighAiWarehouseJobRequest::class.java) }
        catch (e: Exception) { return manual(command, "Stored Warehouse Job payload is invalid: ${e.message}") }
        val siMeta = try { objectMapper.readTree(command.payload) }
        catch (e: Exception) { return manual(command, "Stored Sales Invoice command is invalid: ${e.message}") }
        val externalReference = siMeta.requiredText("externalReference")
            ?: return manual(command, "Sales Invoice external reference is missing")
        val expected = buildRequest(local, wj, jobId, jobNo, externalReference)
        val remote = when (val lookup = invoiceClient.findInvoiceByExternalReference("WMS", externalReference, authToken)) {
            is InvoiceLookupResult.Found -> lookup.invoice
            InvoiceLookupResult.NotFound -> when (val create = invoiceClient.createInvoiceV1(expected, command.idempotencyKey, authToken)) {
                is InvoiceV1CreationResult.Success -> create.invoice
                is InvoiceV1CreationResult.Indeterminate -> return recover(command, expected, externalReference, wj, jobId, authToken)
                is InvoiceV1CreationResult.Rejected -> {
                    val exact = invoiceClient.findInvoiceByExternalReference("WMS", externalReference, authToken)
                    if (exact is InvoiceLookupResult.Found) exact.invoice else return manual(command, create.errorMessage)
                }
            }
            is InvoiceLookupResult.Unavailable -> return retry(command, lookup.errorMessage)
        }
        val validation = validateInvoice(remote, expected, jobId)
        if (validation != null) return manual(command, validation)
        val allocationResult = ensureAllocations(command, remote, wj, jobId, authToken)
        if (allocationResult !is OutboxCommandResult.Success) return allocationResult
        return if (binding.bindSalesInvoice(command, remote)) invoiceSuccess(remote, wj)
        else manual(command, "Local Sales Invoice binding lost its payload fence")
    }

    private fun buildRequest(local: WmsBillingInvoice, wj: CreateFreighAiWarehouseJobRequest, jobId: String, jobNo: String, ext: String): CreateFreighAiInvoiceRequest {
        val categories = buildList {
            local.storageLines.sortedBy { "${it.projectCode}:${it.description}:${it.isMinimumTopUp}" }
                .forEach { add("WAREHOUSE_STORAGE") }
            local.movementLines.sortedBy { "${it.direction}:${it.projectCode}" }.forEach {
                add(if (it.direction == MovementDirection.INBOUND) "WAREHOUSE_INBOUND" else "WAREHOUSE_OUTBOUND")
            }
            local.serviceLines.sortedBy { "${it.serviceCode}:${it.projectCode}" }.forEach { add("WAREHOUSE_SERVICE") }
        }
        require(categories.size == wj.commercialSnapshot.sellingLines.size) {
            "Frozen WMS selling lines do not match the Warehouse Job snapshot"
        }
        val lines = wj.commercialSnapshot.sellingLines.mapIndexed { index, line ->
            val flatFee = line.quantity.signum() == 0 && line.unitPrice.signum() == 0 && line.netAmount.signum() > 0
            FreighAiInvoiceLineItem(
                lineId = line.lineId,
                description = line.description,
                quantity = if (flatFee) BigDecimal.ONE else line.quantity,
                unit = if (flatFee) "month" else line.unit,
                unitPrice = if (flatFee) line.netAmount else line.unitPrice,
                chargeTypeId = requireNotNull(line.serviceCode) { "Warehouse selling line ${line.lineId} has no ChargeType" },
                warehouseAccountingCategory = categories[index],
                vatPercent = line.taxPercent,
                vatAmount = line.taxAmount
            )
        }
        return CreateFreighAiInvoiceRequest(
            invoiceDate = requireNotNull(local.generatedAt) { "Frozen V1 invoice has no generatedAt" }
                .atZone(ZoneOffset.UTC).toLocalDate(),
            partyId = wj.customerSnapshot.customerId,
            purpose = FreighAiInvoiceContracts.WAREHOUSING,
            jobLinkContractVersion = FreighAiInvoiceContracts.GENERIC_JOB_V1,
            linkedJobs = listOf(FreighAiLinkedJob(jobId, "WAREHOUSE", jobNo, wj.customerSnapshot.customerId)),
            sourceSystem = FreighAiInvoiceContracts.SOURCE_WMS,
            externalReference = ext,
            currencyId = currencyId,
            referenceNo = local.freighaiReferenceNo,
            narration = "WMS warehouse charges — ${local.billingMonth}",
            lineItems = lines
        )
    }

    private fun validateInvoice(remote: FreighAiInvoiceResponse, expected: CreateFreighAiInvoiceRequest, jobId: String): String? = when {
        remote.externalReference != expected.externalReference || remote.sourceSystem != "WMS" -> "Recovered Sales Invoice external identity differs"
        remote.invoiceType != "SALES" -> "Recovered Finance document is not a Sales Invoice"
        remote.jobLinkContractVersion != FreighAiInvoiceContracts.GENERIC_JOB_V1 -> "Recovered Sales Invoice is not GENERIC_JOB_V1"
        remote.linkedJobs.none { it.jobId == jobId && it.jobCategory == "WAREHOUSE" } -> "Recovered Sales Invoice targets another Job"
        remote.currentStatus?.uppercase() != "DRAFT" -> "Sales Invoice is not a draft and its allocation/binding was not confirmed"
        else -> null
    }

    private fun ensureAllocations(
        command: WarehouseJobOutbox,
        invoice: FreighAiInvoiceResponse,
        wj: CreateFreighAiWarehouseJobRequest,
        jobId: String,
        authToken: String
    ): OutboxCommandResult {
        val currency = invoice.currency?.code ?: wj.commercialSnapshot.currencyCode
        val expected = wj.commercialSnapshot.sellingLines.map { line ->
            FreighAiJobAllocationItem(
                allocationKey = "sales:${invoice.invoiceId}:${line.lineId}:$jobId",
                invoiceLineId = line.lineId,
                target = "JOB",
                jobId = jobId,
                jobLineId = line.lineId,
                netAmount = line.netAmount,
                vatAmount = line.taxAmount,
                grossAmount = line.grossAmount,
                documentCurrency = currency,
                baseCurrency = currency,
                baseNetAmount = line.netAmount,
                baseVatAmount = line.taxAmount,
                baseGrossAmount = line.grossAmount
            )
        }
        val existing = invoiceClient.getJobAllocationsV1(invoice.invoiceId, authToken)
        if (existing is InvoiceAllocationLookupResult.Found && allocationsMatch(existing.allocations, expected)) {
            return allocationSuccess(invoice.invoiceId, expected)
        }
        if (existing is InvoiceAllocationLookupResult.Unavailable && invoice.allocationRevision > 0) {
            return retry(command, "Cannot verify existing Sales Invoice allocations: ${existing.errorMessage}")
        }
        return when (val result = invoiceClient.replaceJobAllocationsV1(
            invoice.invoiceId,
            ReplaceFreighAiJobAllocationsRequest(invoice.allocationRevision, expected),
            command.idempotencyKey + "-allocations", authToken, "wms-saga"
        )) {
            is InvoiceAllocationMutationResult.Success -> allocationSuccess(invoice.invoiceId, expected)
            is InvoiceAllocationMutationResult.Indeterminate -> verifyAllocations(command, invoice.invoiceId, expected, result.errorMessage, authToken)
            is InvoiceAllocationMutationResult.Rejected -> verifyAllocations(command, invoice.invoiceId, expected, result.errorMessage, authToken, rejected = true)
        }
    }

    private fun verifyAllocations(command: WarehouseJobOutbox, invoiceId: String, expected: List<FreighAiJobAllocationItem>, error: String, authToken: String, rejected: Boolean = false) =
        when (val lookup = invoiceClient.getJobAllocationsV1(invoiceId, authToken)) {
            is InvoiceAllocationLookupResult.Found -> if (allocationsMatch(lookup.allocations, expected)) allocationSuccess(invoiceId, expected)
                else if (rejected) manual(command, error) else retry(command, "Allocation outcome is not yet visible: $error")
            is InvoiceAllocationLookupResult.Unavailable -> retry(command, "Allocation recovery read failed: ${lookup.errorMessage}")
        }

    private fun allocationsMatch(actual: List<FreighAiJobAllocationResponse>, expected: List<FreighAiJobAllocationItem>): Boolean {
        if (actual.size != expected.size) return false
        val byKey = actual.associateBy { it.allocationKey }
        return expected.all { e -> byKey[e.allocationKey]?.let { a ->
            a.invoiceLineId == e.invoiceLineId && a.target == e.target && a.jobId == e.jobId &&
                a.jobLineId == e.jobLineId && a.costLineId == null &&
                a.netAmount.compareTo(e.netAmount) == 0 && a.vatAmount.compareTo(e.vatAmount) == 0 &&
                a.grossAmount.compareTo(e.grossAmount) == 0
        } == true }
    }

    private fun recover(command: WarehouseJobOutbox, expected: CreateFreighAiInvoiceRequest, ext: String, wj: CreateFreighAiWarehouseJobRequest, jobId: String, authToken: String): OutboxCommandResult =
        when (val exact = invoiceClient.findInvoiceByExternalReference("WMS", ext, authToken)) {
            is InvoiceLookupResult.Found -> {
                val error = validateInvoice(exact.invoice, expected, jobId)
                if (error != null) manual(command, error)
                else {
                    val allocation = ensureAllocations(command, exact.invoice, wj, jobId, authToken)
                    if (allocation is OutboxCommandResult.Success && binding.bindSalesInvoice(command, exact.invoice)) invoiceSuccess(exact.invoice, wj)
                    else allocation
                }
            }
            InvoiceLookupResult.NotFound -> retry(command, "Remote Sales Invoice mutation outcome is not yet visible")
            is InvoiceLookupResult.Unavailable -> retry(command, exact.errorMessage)
        }

    private fun JsonNode.requiredText(name: String): String? = get(name)?.asText()?.takeIf(String::isNotBlank)
    private fun allocationSuccess(invoiceId: String, expected: List<FreighAiJobAllocationItem>) =
        OutboxCommandResult.Success(invoiceId, remoteHash(
            invoiceId,
            expected.joinToString("|") {
                "${it.allocationKey}:${it.invoiceLineId}:${it.jobId}:${it.jobLineId}:${it.netAmount.toPlainString()}:${it.vatAmount.toPlainString()}:${it.grossAmount.toPlainString()}"
            }
        ))

    private fun invoiceSuccess(invoice: FreighAiInvoiceResponse, wj: CreateFreighAiWarehouseJobRequest) =
        OutboxCommandResult.Success(invoice.invoiceId, remoteHash(
            invoice.invoiceId, invoice.invoiceNo, invoice.currentStatus, invoice.externalReference,
            invoice.jobLinkContractVersion, invoice.allocationRevision.toString(),
            wj.commercialSnapshot.sellingLines.joinToString("|") { "${it.lineId}:${it.grossAmount.toPlainString()}" }
        ))

    private fun retry(c: WarehouseJobOutbox, e: String) = OutboxCommandResult.Retry(e).also { binding.failure(c, e, false) }
    private fun manual(c: WarehouseJobOutbox, e: String) = OutboxCommandResult.ManualReview(e).also { binding.failure(c, e, true) }
}

private fun remoteHash(vararg parts: String?): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u001f") { it ?: "<null>" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
