package com.wmspro.tenant.billing.warehousejob.payload

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.wmspro.common.external.freighai.dto.CreateFreighAiWarehouseJobRequest
import com.wmspro.common.external.freighai.dto.WarehouseCommercialSnapshot
import com.wmspro.common.external.freighai.dto.WarehouseContext
import com.wmspro.common.external.freighai.dto.WarehouseCustomerSnapshot
import com.wmspro.common.external.freighai.dto.WarehouseJobPlannedCostLine
import com.wmspro.common.external.freighai.dto.WarehouseJobSellingLine
import com.wmspro.tenant.billing.invoice.WmsBillingInvoice
import com.wmspro.tenant.billing.snapshot.BillingRunCostSnapshot
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.YearMonth

data class BuiltWarehouseJobPayload(
    val request: CreateFreighAiWarehouseJobRequest,
    val canonicalJson: String,
    val sha256: String,
    val byteSize: Int
)

class WarehouseJobPayloadTooLargeException(message: String) : IllegalStateException(message)

@Component
class WarehouseJobPayloadBuilder(objectMapper: ObjectMapper) {
    private val mapper = objectMapper.copy()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun build(
        tenantId: String,
        invoice: WmsBillingInvoice,
        snapshots: List<BillingRunCostSnapshot>,
        customerName: String?,
        currencyId: String,
        payloadVersion: Long,
        freighAiCustomerId: String = invoice.customerId.toString()
    ): BuiltWarehouseJobPayload {
        require(invoice.generationContractVersion == WAREHOUSE_JOB_V1) { "CUTOVER_CONFLICT: invoice is not WAREHOUSE_JOB_V1" }
        require(snapshots.all { it.generationContractVersion == WAREHOUSE_JOB_V1 }) {
            "CUTOVER_CONFLICT: every cost snapshot must be WAREHOUSE_JOB_V1"
        }
        require(snapshots.all {
            !it.costTreatment.isNullOrBlank() && it.completionWeight != null &&
                it.effectiveCostRate != null && it.totalCost != null && !it.freighaiChargeTypeId.isNullOrBlank()
        }) {
            "Every V1 cost snapshot requires explicit treatment, completion weight and monetary cost"
        }
        val requiredCustomerName = requireNotNull(customerName?.takeIf { it.isNotBlank() }) {
            "Warehouse Job customer snapshot requires customerName"
        }
        val frozenAt = requireNotNull(invoice.generatedAt) { "V1 invoice requires a stable generatedAt timestamp" }
        val month = YearMonth.parse(invoice.billingMonth)
        val externalReference = invoice.warehouseJobExternalReference
            ?: "WMS-${invoice.customerId}-${invoice.projectCode ?: "default"}-${invoice.billingMonth}"

        val selling = buildList {
            invoice.storageLines.sortedBy { "${it.projectCode}:${it.description}:${it.isMinimumTopUp}" }.forEach { line ->
                add(
                    WarehouseJobSellingLine(
                        lineId = stableId("SELL", "STORAGE", line.projectCode ?: "default", line.description, line.isMinimumTopUp.toString()),
                        description = line.description,
                        serviceCode = line.freighaiChargeTypeId,
                        quantity = line.cbmDays,
                        unit = "CBM-day",
                        unitPrice = line.ratePerDay,
                        netAmount = line.amount,
                        taxPercent = line.vatPercent,
                        taxAmount = line.vatAmount,
                        grossAmount = line.amount.add(line.vatAmount),
                    )
                )
            }
            invoice.movementLines.sortedBy { "${it.direction}:${it.projectCode}" }.forEach { line ->
                add(
                    WarehouseJobSellingLine(
                        lineId = stableId("SELL", line.direction.name, line.projectCode ?: "default"),
                        description = line.description,
                        serviceCode = line.freighaiChargeTypeId,
                        quantity = line.totalCbm,
                        unit = "CBM",
                        unitPrice = line.ratePerCbm,
                        netAmount = line.amount,
                        taxPercent = line.vatPercent,
                        taxAmount = line.vatAmount,
                        grossAmount = line.amount.add(line.vatAmount),
                        sourceReferences = line.sourceRecordIds.sorted()
                    )
                )
            }
            invoice.serviceLines.sortedBy { "${it.serviceCode}:${it.projectCode}" }.forEach { line ->
                add(
                    WarehouseJobSellingLine(
                        lineId = stableId("SELL", "SERVICE", line.serviceCode, line.projectCode ?: "default"),
                        description = line.description,
                        // This wire field is the Finance ChargeType identity
                        // for every selling-line category. The operational
                        // service code remains in the stable line ID/source.
                        serviceCode = line.freighaiChargeTypeId,
                        quantity = line.quantity,
                        unit = line.unit,
                        unitPrice = line.ratePerUnit,
                        netAmount = line.amount,
                        taxPercent = line.vatPercent,
                        taxAmount = line.vatAmount,
                        grossAmount = line.amount.add(line.vatAmount),
                        sourceReferences = line.serviceLogIds.sorted()
                    )
                )
            }
        }

        val planned = snapshots.sortedBy { it.costLineId ?: it.snapshotId }.map { snapshot ->
            WarehouseJobPlannedCostLine(
                costLineId = snapshot.costLineId ?: stableId("COST", snapshot.sourceType.name, snapshot.sourceRecord.id, snapshot.sourceLineId ?: "record"),
                description = snapshot.sourceRecord.number ?: snapshot.sourceRecord.id,
                costCode = snapshot.serviceCode ?: snapshot.sourceType.name,
                chargeTypeId = snapshot.freighaiChargeTypeId,
                quantity = snapshot.quantity,
                unit = snapshot.unit,
                unitCost = snapshot.effectiveCostRate!!,
                plannedNetAmount = snapshot.totalCost!!,
                treatment = snapshot.costTreatment!!,
                completionWeight = snapshot.completionWeight!!,
                sourceReferences = listOfNotNull(snapshot.sourceRecord.id, snapshot.sourceLineId).distinct()
            )
        }

        require(selling.isNotEmpty()) { "Warehouse Job requires at least one selling line" }
        require(selling.map { it.lineId }.distinct().size == selling.size) { "Warehouse Job selling line IDs must be unique" }
        require(planned.map { it.costLineId }.distinct().size == planned.size) { "Warehouse Job cost line IDs must be unique" }
        require(selling.none { listOf(it.quantity, it.unitPrice, it.netAmount, it.taxAmount, it.grossAmount).any { amount -> amount.signum() < 0 } }) {
            "Warehouse Job selling amounts must not be negative"
        }
        require(planned.none { listOf(it.quantity, it.unitCost, it.plannedNetAmount).any { amount -> amount.signum() < 0 } }) {
            "Warehouse Job planned costs must not be negative"
        }
        val sellingSubtotal = selling.fold(BigDecimal.ZERO) { total, line -> total.add(line.netAmount) }
        val taxTotal = selling.fold(BigDecimal.ZERO) { total, line -> total.add(line.taxAmount) }
        require(sellingSubtotal.compareTo(invoice.subtotal) == 0 && taxTotal.compareTo(invoice.totalVat) == 0) {
            "Frozen invoice totals do not match its Warehouse Job selling lines"
        }
        val plannedCostTotal = planned.fold(BigDecimal.ZERO) { total, line -> total.add(line.plannedNetAmount) }
        val calculationVersions = snapshots.mapNotNull { it.calculationVersion }.distinct()
        require(calculationVersions.size <= 1) { "Warehouse Job cost evidence must use one calculationVersion" }
        val commercial = WarehouseCommercialSnapshot(
            currencyCode = currencyId.removePrefix("CUR-"),
            frozenAt = frozenAt,
            sellingLines = selling,
            plannedCostLines = planned,
            sellingSubtotal = sellingSubtotal,
            taxTotal = taxTotal,
            sellingGrandTotal = sellingSubtotal.add(taxTotal),
            plannedCostTotal = plannedCostTotal,
            plannedProfit = sellingSubtotal.subtract(plannedCostTotal)
        )
        val chargeHash = sha256(mapper.writeValueAsBytes(commercial))
        val sourceDocuments = snapshots.map { "${it.sourceType}:${it.sourceRecord.id}:${it.sourceLineId ?: "record"}" }.sorted()
        val sourceHash = sha256(
            mapper.writeValueAsBytes(
                sortedMapOf(
                    "billingInvoiceId" to invoice.billingInvoiceId,
                    "billingMonth" to invoice.billingMonth,
                    "chargeContentHash" to chargeHash,
                    "projectBucket" to (invoice.projectCode ?: "DEFAULT"),
                    "sourceDocuments" to sourceDocuments.joinToString(",")
                )
            )
        )
        val request = CreateFreighAiWarehouseJobRequest(
            externalReference = externalReference,
            sourceRevision = payloadVersion,
            sourceContentHash = sourceHash,
            customerSnapshot = WarehouseCustomerSnapshot(freighAiCustomerId, requiredCustomerName),
            warehouseContext = WarehouseContext(
                sourceTenantId = tenantId,
                wmsBillingInvoiceId = invoice.billingInvoiceId,
                wmsBillingReference = invoice.freighaiReferenceNo,
                billingMonth = invoice.billingMonth,
                servicePeriodStart = month.atDay(1),
                servicePeriodEnd = month.atEndOfMonth(),
                projectBucket = invoice.projectCode ?: "DEFAULT",
                projectCode = invoice.projectCode,
                sourceContentHash = sourceHash,
                chargeContentHash = chargeHash,
                calculationVersion = calculationVersions.singleOrNull() ?: "WMS_COST_V1",
                sourceDocumentReferences = sourceDocuments
            ),
            commercialSnapshot = commercial
        )
        val json = mapper.writeValueAsString(request)
        val size = json.toByteArray(StandardCharsets.UTF_8).size
        if (size > MAX_SERVICE_PAYLOAD_BYTES) {
            throw WarehouseJobPayloadTooLargeException("Warehouse Job payload is $size bytes; maximum is $MAX_SERVICE_PAYLOAD_BYTES")
        }
        return BuiltWarehouseJobPayload(request, json, sourceHash, size)
    }

    fun stableCostLineId(sourceType: String, sourceId: String, sourceLineId: String? = null): String =
        stableId("COST", sourceType, sourceId, sourceLineId ?: "record")

    private fun stableId(vararg parts: String): String = sha256(parts.joinToString("|").toByteArray()).take(32)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val WAREHOUSE_JOB_V1 = "WAREHOUSE_JOB_V1"
        const val MAX_SERVICE_PAYLOAD_BYTES = 6 * 1024 * 1024
    }
}
