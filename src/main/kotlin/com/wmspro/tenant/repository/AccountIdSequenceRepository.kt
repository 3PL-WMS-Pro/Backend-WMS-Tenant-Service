package com.wmspro.tenant.repository

import com.wmspro.tenant.model.AccountIdSequence
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

/**
 * Repository for AccountIdSequence (per-tenant collection account_id_sequence).
 *
 * Atomic increment is performed via MongoTemplate.findAndModify in
 * AccountIdMappingService — this repository is here for completeness (CRUD
 * operations, presence checks) but is not the primary mutation surface.
 */
@Repository
interface AccountIdSequenceRepository : MongoRepository<AccountIdSequence, String>
