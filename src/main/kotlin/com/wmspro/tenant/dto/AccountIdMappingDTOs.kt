package com.wmspro.tenant.dto

import com.wmspro.tenant.model.AccountIdMapping

/**
 * Wire-format DTO for an AccountIdMapping row.
 *
 * `leadtorevId` is serialized as Long (matches WMS Mongo). Map keys in batch
 * responses are serialized as strings (JSON object keys must be strings).
 */
data class AccountIdMappingDto(
    val leadtorevId: Long,
    val freighaiCustomerId: String,
    val accountCode: String,
    val source: String,
    val notes: String? = null
) {
    companion object {
        fun fromEntity(entity: AccountIdMapping) = AccountIdMappingDto(
            leadtorevId = entity.id,
            freighaiCustomerId = entity.freighaiCustomerId,
            accountCode = entity.accountCode,
            source = entity.source,
            notes = entity.notes
        )
    }
}

/**
 * Request body for /get-or-assign — one entry per FreighAI customer the caller
 * needs a Long for. accountCode is required so synthetic mappings created on
 * the fly carry the correct code (used for label printing, search, etc.).
 */
data class GetOrAssignRequestItem(
    val freighaiCustomerId: String,
    val accountCode: String
)
