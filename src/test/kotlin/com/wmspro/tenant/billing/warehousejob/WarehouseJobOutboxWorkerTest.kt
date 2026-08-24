package com.wmspro.tenant.billing.warehousejob

import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.warehousejob.orchestration.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant
import java.util.Optional

class WarehouseJobOutboxWorkerTest {
    @BeforeEach fun context() {
        TenantContext.setCurrentTenant("7")
        MongoConnectionStorage.setConnection("mongodb://localhost/tenant7")
    }
    @AfterEach fun clear() { TenantContext.clear(); MongoConnectionStorage.clear() }

    @Test
    fun `expired leased command is reclaimed and completed through its new fence`() {
        val mongo = Mockito.mock(MongoTemplate::class.java)
        val outbox = Mockito.mock(WarehouseJobOutboxRepository::class.java)
        val intents = Mockito.mock(WarehouseJobClaimIntentRepository::class.java)
        val binding = Mockito.mock(WarehouseJobBindingService::class.java)
        val handler = Mockito.mock(WarehouseJobOutboxCommandHandler::class.java)
        val stale = command().copy(
            state = WarehouseJobOutboxState.LEASED,
            leaseOwner = "crashed-worker",
            leaseToken = 4,
            leaseUntil = Instant.now().minusSeconds(30)
        )
        val reclaimed = stale.copy(leaseOwner = "worker-new", leaseToken = 5, leaseUntil = Instant.now().plusSeconds(120))
        Mockito.`when`(intents.findById(stale.billingInvoiceId)).thenReturn(Optional.of(intent()))
        Mockito.`when`(handler.supports(stale.operation)).thenReturn(true)
        Mockito.`when`(handler.handle(reclaimed, "jwt")).thenReturn(OutboxCommandResult.Success("WJ-1", "a".repeat(64)))
        Mockito.`when`(
            mongo.findAndModify(any(Query::class.java), any(Update::class.java), any(FindAndModifyOptions::class.java), eq(WarehouseJobOutbox::class.java))
        ).thenReturn(reclaimed, null)

        val worker = WarehouseJobOutboxWorker(mongo, outbox, intents, binding, listOf(handler))
        assertEquals(1, worker.drainCurrentTenant("worker-new", "jwt", 2))

        val captor = ArgumentCaptor.forClass(Query::class.java)
        Mockito.verify(mongo, Mockito.atLeastOnce()).findAndModify(
            captor.capture(), any(Update::class.java), any(FindAndModifyOptions::class.java), eq(WarehouseJobOutbox::class.java)
        )
        val leaseQuery = captor.allValues.first().queryObject.toString()
        assertTrue(leaseQuery.contains("LEASED"), leaseQuery)
        assertTrue(leaseQuery.contains("leaseUntil"), leaseQuery)
        val completionUpdate = Mockito.mockingDetails(mongo).invocations
            .first { it.method.name == "updateFirst" }.arguments[1] as Update
        val completionDocument = completionUpdate.updateObject.toString()
        assertTrue(completionDocument.contains("remoteEntityId=WJ-1"), completionDocument)
        assertTrue(completionDocument.contains("remoteResponseHash=${"a".repeat(64)}"), completionDocument)
    }

    private fun command() = WarehouseJobOutbox(
        outboxId = "out-1", billingInvoiceId = "wmsinv-1", payloadVersion = 1,
        operation = WarehouseJobOutboxOperation.UPSERT_WJ, idempotencyKey = "key",
        payload = "{}", payloadHash = "hash", correlationId = "corr"
    )

    private fun intent() = WarehouseJobClaimIntent(
        billingInvoiceId = "wmsinv-1", payloadVersion = 1, customerId = 1,
        projectBucket = "DEFAULT", billingMonth = "2026-09", sourceFingerprint = "hash",
        state = WarehouseJobClaimIntentState.READY, expiresAt = Instant.now()
    )
}
