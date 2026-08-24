package com.wmspro.tenant.billing.servicelog

import com.wmspro.common.billing.BillingSourceClaim
import com.wmspro.common.billing.BillingSourceClaimState
import com.wmspro.common.billing.ReserveBillingSourceClaimRequest
import com.wmspro.common.billing.TransitionBillingSourceClaimRequest
import com.wmspro.common.billing.WarehouseJobGenerationContracts
import com.wmspro.tenant.billing.invoice.WmsBillingInvoiceRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentState
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant

class BillingClaimConflictException(message: String) : IllegalStateException(message)

@Service
class ServiceLogBillingClaimService(
    private val mongoTemplate: MongoTemplate,
    private val invoiceRepository: WmsBillingInvoiceRepository,
    private val intentRepository: WarehouseJobClaimIntentRepository
) {
    fun reserve(serviceLogId: String, request: ReserveBillingSourceClaimRequest): ServiceLog {
        validateV1Owner(request)
        val now = Instant.now()
        val claim = BillingSourceClaim(
            generationContractVersion = WarehouseJobGenerationContracts.V1,
            billingInvoiceId = request.billingInvoiceId,
            claimOwnerKey = request.claimOwnerKey,
            sourceLineId = request.sourceLineId,
            accountingPeriod = request.accountingPeriod,
            state = BillingSourceClaimState.RESERVED,
            claimVersion = request.claimVersion,
            expiresAt = request.expiresAt,
            reservedAt = now,
            updatedAt = now
        )
        val query = Query.query(
            Criteria.where("_id").`is`(serviceLogId)
                .and("billingInvoiceId").`is`(null)
                .orOperator(
                    Criteria.where("billingClaim").exists(false),
                    Criteria.where("billingClaim").`is`(null),
                    Criteria().andOperator(
                        Criteria.where("billingClaim.generationContractVersion").`is`(WarehouseJobGenerationContracts.V1),
                        Criteria.where("billingClaim.claimOwnerKey").`is`(request.claimOwnerKey),
                        Criteria.where("billingClaim.claimVersion").`is`(request.claimVersion),
                        Criteria.where("billingClaim.state").`is`(BillingSourceClaimState.RESERVED)
                    )
                )
        )
        return mongoTemplate.findAndModify(
            query,
            Update().set("billingClaim", claim),
            FindAndModifyOptions.options().returnNew(true),
            ServiceLog::class.java
        ) ?: conflict(serviceLogId)
    }

    fun commit(serviceLogId: String, request: TransitionBillingSourceClaimRequest): ServiceLog =
        transition(serviceLogId, request, BillingSourceClaimState.RESERVED, BillingSourceClaimState.COMMITTED)

    fun release(serviceLogId: String, request: TransitionBillingSourceClaimRequest): ServiceLog =
        transition(serviceLogId, request, BillingSourceClaimState.RESERVED, BillingSourceClaimState.RELEASED)

    private fun transition(
        id: String,
        request: TransitionBillingSourceClaimRequest,
        from: BillingSourceClaimState,
        to: BillingSourceClaimState
    ): ServiceLog {
        require(request.generationContractVersion == WarehouseJobGenerationContracts.V1) { "CUTOVER_CONFLICT: V1 marker required" }
        val query = Query.query(
            Criteria.where("_id").`is`(id)
                .and("billingInvoiceId").`is`(null)
                .and("billingClaim.generationContractVersion").`is`(WarehouseJobGenerationContracts.V1)
                .and("billingClaim.claimOwnerKey").`is`(request.claimOwnerKey)
                .and("billingClaim.claimVersion").`is`(request.claimVersion)
                .and("billingClaim.state").`is`(from)
        )
        val changed = mongoTemplate.findAndModify(
            query,
            Update()
                .set("billingClaim.state", to)
                .set("billingClaim.updatedAt", Instant.now())
                .inc("billingClaim.claimVersion", 1)
                .unset("billingClaim.expiresAt"),
            FindAndModifyOptions.options().returnNew(true),
            ServiceLog::class.java
        )
        if (changed != null) return changed
        val existing = mongoTemplate.findById(id, ServiceLog::class.java)
        if (existing?.billingInvoiceId == null && existing?.billingClaim?.let {
                it.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                    it.claimOwnerKey == request.claimOwnerKey && it.claimVersion == request.claimVersion + 1 && it.state == to
            } == true) return existing
        return conflict(id)
    }

    private fun validateV1Owner(request: ReserveBillingSourceClaimRequest) {
        require(request.generationContractVersion == WarehouseJobGenerationContracts.V1) { "CUTOVER_CONFLICT: V1 marker required" }
        val invoice = invoiceRepository.findById(request.billingInvoiceId).orElse(null)
        val intent = intentRepository.findById(request.billingInvoiceId).orElse(null)
        val authorized = (invoice?.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
            invoice.warehouseJobPayloadVersion == request.payloadVersion) ||
            (intent?.generationContractVersion == WarehouseJobGenerationContracts.V1 &&
                intent.payloadVersion == request.payloadVersion &&
                intent.state == WarehouseJobClaimIntentState.CLAIMING && intent.expiresAt.isAfter(Instant.now()))
        if (!authorized) {
            throw BillingClaimConflictException("CUTOVER_CONFLICT: owning billing invoice is not the requested V1 revision")
        }
    }

    private fun conflict(id: String): Nothing {
        val existing = mongoTemplate.findById(id, ServiceLog::class.java)
            ?: throw IllegalArgumentException("ServiceLog '$id' not found")
        val reason = when {
            existing.billingInvoiceId != null -> "legacy billing lock '${existing.billingInvoiceId}' exists"
            existing.billingClaim?.generationContractVersion != WarehouseJobGenerationContracts.V1 -> "record is not V1 claim-compatible"
            else -> "claim owner/version/state mismatch"
        }
        throw BillingClaimConflictException("CUTOVER_CONFLICT: ServiceLog '$id' $reason")
    }
}
