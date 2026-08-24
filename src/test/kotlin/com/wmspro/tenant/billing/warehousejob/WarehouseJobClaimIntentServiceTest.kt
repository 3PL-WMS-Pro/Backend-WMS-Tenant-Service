package com.wmspro.tenant.billing.warehousejob

import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntent
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentConflict
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentRepository
import com.wmspro.tenant.billing.warehousejob.orchestration.WarehouseJobClaimIntentService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Optional

class WarehouseJobClaimIntentServiceTest {
    @Test
    fun `parallel exact generator cannot adopt the winners active claims`() {
        val repository = Mockito.mock(WarehouseJobClaimIntentRepository::class.java)
        val intent = WarehouseJobClaimIntent(
            billingInvoiceId = "wmsinv-1", payloadVersion = 1, customerId = 7,
            projectBucket = "DEFAULT", billingMonth = "2026-09", sourceFingerprint = "fingerprint",
            expiresAt = Instant.parse("2026-09-01T00:15:00Z")
        )
        Mockito.`when`(repository.findById(intent.billingInvoiceId)).thenReturn(Optional.of(intent))

        assertThrows(WarehouseJobClaimIntentConflict::class.java) {
            WarehouseJobClaimIntentService(repository).createOrAdopt(intent)
        }
        Mockito.verify(repository, Mockito.never()).save(intent)
    }
}
