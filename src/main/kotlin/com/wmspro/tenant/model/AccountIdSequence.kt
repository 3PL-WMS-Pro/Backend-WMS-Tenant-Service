package com.wmspro.tenant.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * AccountIdSequence — atomic counter for minting synthetic Long IDs when WMS
 * encounters a FreighAI customer that doesn't yet have a corresponding
 * leadtorev account ID.
 *
 * Collection: account_id_sequence
 * Database: per-tenant (e.g. wms_pro_tenant_199)
 *
 * Single document with _id = "next_id". Incremented atomically via
 * MongoTemplate.findAndModify($inc) in AccountIdMappingService. Seeded with
 * value = 1,000,000 by scripts/seed_account_id_mapping.py (Phase 2) — well
 * above any leadtorev ID range to guarantee no collisions with existing rows.
 */
@Document(collection = "account_id_sequence")
data class AccountIdSequence(
    @Id
    val id: String,                  // always "next_id"
    val value: Long,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
) {
    companion object {
        const val NEXT_ID_DOC = "next_id"
        const val SYNTHETIC_START = 1_000_000L
    }
}
