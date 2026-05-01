package com.wmspro.tenant.billing.invoice

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * BillingRunSummary — one row per cron / manual sweep, recording which
 * customers succeeded, skipped, and failed. Drives the BillingRunsScreen
 * (Phase 10) admin dashboard.
 *
 * Persisted in the per-tenant DB: each tenant sees its own run history.
 * Cleared by future operational tooling if storage matters; for now we
 * keep all rows.
 */
@Document(collection = "billing_run_summary")
data class BillingRunSummary(
    @Id
    val runId: String,

    @Indexed
    val billingMonth: String,

    val triggeredAt: Instant,
    val triggeredBy: String,

    /** "CRON" | "MANUAL". Cron runs are scheduled; manual are admin-triggered. */
    val triggerType: String,

    val succeededCustomerIds: List<Long> = emptyList(),
    val skippedCustomerIds: List<Long> = emptyList(),
    val failedCustomerIds: List<Long> = emptyList(),

    val skipReasons: Map<String, String> = emptyMap(),  // customerId.toString() → reason
    val failureMessages: Map<String, String> = emptyMap(),

    val durationMs: Long = 0
)
