package com.wmspro.tenant.billing.warehousejob.orchestration

import com.wmspro.common.mongo.MongoConnectionStorage
import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Tenant-aware dispatcher. Command leases provide cross-instance fencing. */
@Component
class WarehouseJobOutboxScheduler(
    private val tenants: TenantDatabaseMappingRepository,
    private val worker: WarehouseJobOutboxWorker
) {
    @Value("\${app.external-api.freighai.service-account-jwt:}")
    private lateinit var serviceJwt: String
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val workerId = "wms-wj-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${billing.warehouse-jobs.worker-delay-ms:15000}")
    fun drain() {
        if (serviceJwt.isBlank()) {
            logger.debug("Warehouse Job background drain is dormant because no service-account JWT is configured")
            return
        }
        if (!running.compareAndSet(false, true)) return
        try {
            tenants.findAll().forEach { tenant ->
                try {
                    TenantContext.setCurrentTenant(tenant.clientId.toString())
                    MongoConnectionStorage.setConnection(tenant.mongoConnection.url)
                    worker.drainCurrentTenant(workerId, serviceJwt)
                } catch (e: Exception) {
                    logger.error("Warehouse Job outbox drain failed for tenant={}", tenant.clientId, e)
                } finally {
                    MongoConnectionStorage.clear()
                    TenantContext.clear()
                }
            }
        } finally {
            running.set(false)
        }
    }
}
