package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * AccountIdMapping — translation table between leadtorev account IDs (Long, used by
 * existing WMS warehouse documents) and FreighAI customer IDs (String, used by the
 * new external customer-master provider).
 *
 * Collection: account_id_mapping
 * Database: per-tenant (e.g. wms_pro_tenant_199)
 *
 * Seeded by scripts/seed_account_id_mapping.py (Phase 2). New rows are added at
 * runtime by AccountIdMappingService.batchGetOrAssign when WMS encounters a
 * FreighAI customer that has no Long ID yet (synthetic Longs, ≥ 1,000,000).
 *
 * The reverse-lookup index (idx_freighaiCustomerId on freighaiCustomerId) is
 * non-unique because two leadtorev IDs may legitimately map to the same FreighAI
 * customer (consolidation case — see Sinotrans 5802 + VOPXKJ 6834).
 */
@Document(collection = "account_id_mapping")
data class AccountIdMapping(
    @Id
    val id: Long,                          // leadtorev account id (or synthetic ≥ 1,000,000)

    @Indexed(name = "idx_freighaiCustomerId")
    val freighaiCustomerId: String,        // cust_xxx in FreighAI

    val accountCode: String,               // 6-letter code (mirrors FreighAI's accountCode)

    val source: String,                    // see SOURCE_* constants

    val notes: String? = null,             // free-text, optional

    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        const val SOURCE_LEADTOREV_MATCH = "leadtorev_match"
        const val SOURCE_LEADTOREV_MANUAL = "leadtorev_manual"
        const val SOURCE_LEADTOREV_CONSOLIDATED = "leadtorev_consolidated"
        const val SOURCE_FREIGHAI_CREATED = "freighai_created"
        const val SOURCE_SYNTHETIC = "synthetic"
    }
}
