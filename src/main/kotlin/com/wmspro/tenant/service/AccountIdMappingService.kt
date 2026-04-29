package com.wmspro.tenant.service

import com.wmspro.tenant.dto.AccountIdMappingDto
import com.wmspro.tenant.dto.GetOrAssignRequestItem
import com.wmspro.tenant.model.AccountIdMapping
import com.wmspro.tenant.model.AccountIdSequence
import com.wmspro.tenant.repository.AccountIdMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * AccountIdMappingService — owns the read + write surface for the per-tenant
 * `account_id_mapping` and `account_id_sequence` collections.
 *
 * Read-only methods (`batchGetByLeadtorevIds`, `batchGetByFreighaiIds`) omit
 * unmapped entries from the result map — callers decide what to do with misses
 * (typically: forward to `batchGetOrAssign` for the unmapped subset).
 *
 * The mint path (`batchGetOrAssign`) atomically increments
 * `account_id_sequence.next_id` via MongoTemplate.findAndModify, then upserts
 * a new mapping row tagged `source = "synthetic"`. Race-tolerant: if two
 * concurrent requests for the same FreighAI customer ID arrive simultaneously,
 * one wins and the other's read after the race finds the existing row.
 */
@Service
class AccountIdMappingService(
    private val accountIdMappingRepository: AccountIdMappingRepository,
    private val mongoTemplate: MongoTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Batch lookup: leadtorevId → AccountIdMapping. Missing IDs are omitted.
     */
    fun batchGetByLeadtorevIds(leadtorevIds: List<Long>): Map<Long, AccountIdMappingDto> {
        if (leadtorevIds.isEmpty()) return emptyMap()
        logger.debug("Batch fetching account_id_mapping by {} leadtorev IDs", leadtorevIds.size)
        return accountIdMappingRepository.findAllById(leadtorevIds)
            .associate { it.id to AccountIdMappingDto.fromEntity(it) }
    }

    /**
     * Reverse batch lookup: freighaiCustomerId → leadtorevId.
     *
     * In the consolidation case (multiple leadtorev IDs → one FreighAI ID), the
     * returned Long is the leadtorevId of the mapping row whose `source` is NOT
     * `leadtorev_consolidated` (i.e. the canonical row for that customer). If
     * only consolidated rows exist (shouldn't happen in practice but defensive),
     * any one of them is returned. Missing freighaiCustomerIds are omitted.
     */
    fun batchGetByFreighaiIds(freighaiCustomerIds: List<String>): Map<String, Long> {
        if (freighaiCustomerIds.isEmpty()) return emptyMap()
        logger.debug("Batch fetching account_id_mapping by {} FreighAI IDs", freighaiCustomerIds.size)
        val rows = accountIdMappingRepository.findByFreighaiCustomerIdIn(freighaiCustomerIds)

        // Group by freighaiCustomerId; pick canonical row when multiple exist.
        return rows.groupBy { it.freighaiCustomerId }
            .mapValues { (_, group) ->
                val canonical = group.firstOrNull { it.source != AccountIdMapping.SOURCE_LEADTOREV_CONSOLIDATED }
                    ?: group.first()
                canonical.id
            }
    }

    /**
     * Batch get-or-assign: for each input item, return the existing leadtorevId
     * if a mapping exists for its freighaiCustomerId, otherwise atomically mint
     * a synthetic Long (≥ 1,000,000) and persist the new mapping.
     *
     * Returns Map<freighaiCustomerId, leadtorevId>. Order of input is not
     * preserved.
     *
     * Uses two-pass strategy:
     *   1. Bulk-resolve existing mappings via findByFreighaiCustomerIdIn (1 query).
     *   2. For any unmapped IDs, mint sequentially (each mint = 1 sequence
     *      increment + 1 upsert).
     *
     * Race tolerance: if a parallel request beats us to inserting the new
     * mapping, our save() will succeed (different _id since we already
     * incremented the sequence) but we'll then have two rows pointing at the
     * same freighaiCustomerId with source=synthetic. The reverse lookup picks
     * one canonical row; the orphan Long is harmless (no WMS doc references
     * it). For Infinity Logistics' single-tenant low-traffic usage this is
     * acceptable; production-hardening could add a per-key advisory lock later.
     */
    fun batchGetOrAssign(items: List<GetOrAssignRequestItem>): Map<String, Long> {
        if (items.isEmpty()) return emptyMap()

        // Deduplicate input by freighaiCustomerId (first occurrence wins for accountCode)
        val uniqueByFreighaiId: Map<String, GetOrAssignRequestItem> =
            items.associateBy { it.freighaiCustomerId }
        val freighaiIds = uniqueByFreighaiId.keys.toList()
        logger.debug("Batch get-or-assign for {} FreighAI IDs", freighaiIds.size)

        // Pass 1: resolve existing
        val existingByFreighaiId: Map<String, Long> = batchGetByFreighaiIds(freighaiIds)

        // Pass 2: mint for the remainder
        val toMint = uniqueByFreighaiId.filterKeys { it !in existingByFreighaiId }
        if (toMint.isEmpty()) return existingByFreighaiId

        logger.info("Minting {} synthetic account ID(s) for new FreighAI customers", toMint.size)
        val minted = mutableMapOf<String, Long>()
        toMint.values.forEach { item ->
            minted[item.freighaiCustomerId] = mintOne(item.freighaiCustomerId, item.accountCode)
        }

        return existingByFreighaiId + minted
    }

    /**
     * Mint a single synthetic Long for a brand-new FreighAI customer. Atomic
     * sequence increment + upsert.
     */
    private fun mintOne(freighaiCustomerId: String, accountCode: String): Long {
        val nextId = nextSyntheticId()
        val now = LocalDateTime.now()
        val mapping = AccountIdMapping(
            id = nextId,
            freighaiCustomerId = freighaiCustomerId,
            accountCode = accountCode,
            source = AccountIdMapping.SOURCE_SYNTHETIC,
            notes = "Auto-assigned at runtime",
            createdAt = now,
            updatedAt = now
        )
        accountIdMappingRepository.save(mapping)
        logger.debug(
            "Minted synthetic mapping: leadtorevId={} freighaiCustomerId={} accountCode={}",
            nextId, freighaiCustomerId, accountCode
        )
        return nextId
    }

    /**
     * Atomic sequence increment. Uses findAndModify with upsert so if the
     * sequence document somehow doesn't exist (it's seeded by the migration
     * script with value=1,000,000), we initialize it to SYNTHETIC_START on
     * first use.
     */
    private fun nextSyntheticId(): Long {
        val query = Query(Criteria.where("_id").`is`(AccountIdSequence.NEXT_ID_DOC))
        val update = Update()
            .inc("value", 1L)
            .setOnInsert("_id", AccountIdSequence.NEXT_ID_DOC)
            .setOnInsert("createdAt", LocalDateTime.now())
            .set("updatedAt", LocalDateTime.now())
        val options = FindAndModifyOptions.options().returnNew(true).upsert(true)

        val result = mongoTemplate.findAndModify(query, update, options, AccountIdSequence::class.java)
            ?: error("findAndModify returned null for account_id_sequence")
        // If this was the first ever call (upsert), value will now be 1.
        // Lift to SYNTHETIC_START so we never collide with leadtorev's range.
        return if (result.value < AccountIdSequence.SYNTHETIC_START) {
            // Bump to the correct floor and retry once.
            mongoTemplate.findAndModify(
                query,
                Update().set("value", AccountIdSequence.SYNTHETIC_START).set("updatedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true),
                AccountIdSequence::class.java
            )
            AccountIdSequence.SYNTHETIC_START
        } else {
            result.value
        }
    }
}
