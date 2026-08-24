package com.wmspro.tenant.billing.warehousejob

import com.wmspro.tenant.billing.invoice.deriveWarehouseJobLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WarehouseJobLifecycleProjectionTest {
    @Test
    fun `authoritative issued and reverted SI states drive only V1 warehouse lifecycle`() {
        assertEquals("BILLED", deriveWarehouseJobLifecycle("WAREHOUSE_JOB_V1", "ACTIVE", "SENT"))
        assertEquals("BILLED", deriveWarehouseJobLifecycle("WAREHOUSE_JOB_V1", "ACTIVE", "PARTIALLY_PAID"))
        assertEquals("BILLED", deriveWarehouseJobLifecycle("WAREHOUSE_JOB_V1", "ACTIVE", "PAID"))
        assertEquals("ACTIVE", deriveWarehouseJobLifecycle("WAREHOUSE_JOB_V1", "BILLED", "DRAFT"))
        assertEquals("CANCELLED", deriveWarehouseJobLifecycle("WAREHOUSE_JOB_V1", "CANCELLED", "SENT"))
        assertEquals(null, deriveWarehouseJobLifecycle(null, null, "SENT"))
    }
}
