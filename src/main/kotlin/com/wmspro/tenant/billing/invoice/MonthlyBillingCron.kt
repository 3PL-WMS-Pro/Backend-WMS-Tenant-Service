package com.wmspro.tenant.billing.invoice

import com.wmspro.common.tenant.TenantContext
import com.wmspro.tenant.billing.profile.CustomerBillingProfileRepository
import com.wmspro.tenant.repository.TenantDatabaseMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.YearMonth
import java.util.UUID

/**
 * Monthly billing cron — runs at 02:00 on the 1st of every month and sweeps
 * every billingEnabled customer in every tenant DB, generating an invoice
 * for the previous month.
 *
 * Skipped without alert when no service-account JWT is configured (early
 * deployments before Phase 11). Per user direction, email alerting is
 * deferred — admin reviews the BillingRunSummary on the BillingRunsScreen
 * to spot failures.
 *
 * Each run persists a BillingRunSummary in the tenant DB regardless of
 * outcome — admin can audit later via `GET /api/v1/billing-runs/summaries`.
 */
@Component
class MonthlyBillingCron(
    private val tenantDatabaseMappingRepository: TenantDatabaseMappingRepository,
    private val billingProfileRepository: CustomerBillingProfileRepository,
    private val billingRunService: BillingRunService,
    private val billingRunSummaryRepository: BillingRunSummaryRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Value("\${app.external-api.freighai.service-account-jwt:}")
    private lateinit var serviceAccountJwt: String

    /**
     * `0 0 2 1 * *` — first of month, 02:00 server-time. Runs across all
     * tenants registered in the central tenant_database_mappings collection.
     */
    @Scheduled(cron = "0 0 2 1 * *")
    fun runMonthlyBilling() {
        if (serviceAccountJwt.isBlank()) {
            logger.warn("MonthlyBillingCron: no service-account JWT configured; skipping run.")
            return
        }
        val billingMonth = previousYearMonth()
        val tenants = tenantDatabaseMappingRepository.findAll()
        logger.info(
            "MonthlyBillingCron: starting sweep for month={} across {} tenants",
            billingMonth, tenants.size
        )
        for (tenant in tenants) {
            try {
                TenantContext.setCurrentTenant(tenant.clientId.toString())
                runForOneTenant(billingMonth, tenant.clientId.toString())
            } catch (e: Exception) {
                logger.error("MonthlyBillingCron: tenant {} failed entirely", tenant.clientId, e)
            } finally {
                TenantContext.clear()
            }
        }
        logger.info("MonthlyBillingCron: sweep complete for month={}", billingMonth)
    }

    private fun runForOneTenant(billingMonth: String, tenantId: String) {
        val started = System.currentTimeMillis()
        val customers = billingProfileRepository.findByBillingEnabled(true)
        if (customers.isEmpty()) {
            logger.info("MonthlyBillingCron: tenant {} has no billing-enabled customers; skipping", tenantId)
            return
        }

        val succeeded = mutableListOf<Long>()
        val skipped = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        val skipReasons = mutableMapOf<String, String>()
        val failureMessages = mutableMapOf<String, String>()

        for (profile in customers) {
            val cid = profile.customerId
            try {
                billingRunService.generate(cid, billingMonth, "CRON", serviceAccountJwt)
                succeeded += cid
            } catch (e: IllegalStateException) {
                skipped += cid
                skipReasons[cid.toString()] = e.message ?: "skipped"
            } catch (e: Exception) {
                logger.error("MonthlyBillingCron: tenant {} customer {} failed", tenantId, cid, e)
                failed += cid
                failureMessages[cid.toString()] = e.message ?: "unknown"
            }
        }

        billingRunSummaryRepository.save(
            BillingRunSummary(
                runId = "run_${UUID.randomUUID().toString().replace("-", "").take(16)}",
                billingMonth = billingMonth,
                triggeredAt = Instant.now(),
                triggeredBy = "CRON",
                triggerType = "CRON",
                succeededCustomerIds = succeeded,
                skippedCustomerIds = skipped,
                failedCustomerIds = failed,
                skipReasons = skipReasons,
                failureMessages = failureMessages,
                durationMs = System.currentTimeMillis() - started
            )
        )
        logger.info(
            "MonthlyBillingCron: tenant {} done — {} succeeded, {} skipped, {} failed",
            tenantId, succeeded.size, skipped.size, failed.size
        )
    }

    private fun previousYearMonth(): String {
        val today = LocalDate.now(ZoneOffset.UTC)
        val previous = YearMonth.from(today).minusMonths(1)
        return previous.toString()
    }
}
