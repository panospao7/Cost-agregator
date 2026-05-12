package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class MerchantNormalizationRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: MerchantNormalizationDao
) {
    suspend fun insertCanonical(merchant: MerchantCanonical): Long {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.insertCanonical")
        return dao.insertCanonical(merchant)
    }

    suspend fun updateCanonical(merchant: MerchantCanonical) {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.updateCanonical")
        dao.updateCanonical(merchant)
    }

    suspend fun getCanonicalById(id: Long): MerchantCanonical? =
        dao.getCanonicalById(id)

    suspend fun getCanonicalBySearchKey(searchKey: String): MerchantCanonical? =
        dao.getCanonicalBySearchKey(searchKey)

    suspend fun getCanonicalByName(name: String): MerchantCanonical? =
        dao.getCanonicalByName(name)

    suspend fun getAllCanonicals(): List<MerchantCanonical> =
        dao.getAllCanonicals()

    suspend fun getTopMerchants(limit: Int): List<MerchantCanonical> =
        dao.getTopMerchants(limit)

    suspend fun updateCanonicalCategory(id: Long, categoryId: Long?) {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.updateCanonicalCategory")
        dao.updateCanonicalCategory(id, categoryId)
    }

    // TODO (C08): incrementMerchantStats is never called — wire it from TransactionSideEffectDispatcher
    // after committed expense creation/update.
    suspend fun incrementMerchantStats(id: Long, amount: Double, timestamp: Long) {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.incrementMerchantStats")
        dao.incrementMerchantStats(id, amount, timestamp)
    }

    suspend fun insertAlias(alias: MerchantAlias): Long {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.insertAlias")
        val id = dao.insertAlias(alias)
        // E3-001: If insert was ignored (alias already exists for same normalizedKey),
        // update the existing alias's occurrenceCount and lastUsedAt.
        if (id <= 0L) {
            val existing = dao.getAliasByNormalizedKey(alias.normalizedKey)
            if (existing != null) {
                dao.updateAlias(existing.copy(
                    occurrenceCount = existing.occurrenceCount + 1,
                    lastUsedAt = alias.lastUsedAt
                ))
                return existing.id
            }
        }
        return id
    }

    suspend fun updateAlias(alias: MerchantAlias) {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.updateAlias")
        dao.updateAlias(alias)
    }

    suspend fun getAliasById(id: Long): MerchantAlias? =
        dao.getAliasById(id)

    suspend fun getAliasByRawName(rawName: String): MerchantAlias? =
        dao.getAliasByRawName(rawName)

    suspend fun getAliasByNormalizedKey(normalizedKey: String): MerchantAlias? =
        dao.getAliasByNormalizedKey(normalizedKey)

    suspend fun getAliasesForCanonical(canonicalId: Long): List<MerchantAlias> =
        dao.getAliasesForCanonical(canonicalId)

    suspend fun searchAliases(query: String, limit: Int = 20): List<MerchantAlias> {
        val normalizedQuery = MerchantKeyGenerator.generate(query)
        if (normalizedQuery.isBlank()) return emptyList()

        val prefixMatches = dao.searchAliasesByPrefix(normalizedQuery, limit)
        if (prefixMatches.size >= limit) {
            return prefixMatches
        }

        val remaining = limit - prefixMatches.size
        val containsMatches = dao.searchAliasesByContains(normalizedQuery, remaining * 3)
        if (containsMatches.isEmpty()) {
            return prefixMatches
        }

        val seenIds = prefixMatches.asSequence().map { it.id }.toHashSet()
        val dedupedContains = containsMatches.filter { seenIds.add(it.id) }
        return (prefixMatches + dedupedContains).take(limit)
    }

    suspend fun deleteUnusedAliasesOlderThan(olderThan: Long): Int {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.deleteUnusedAliasesOlderThan")
        return dao.deleteUnusedAliasesOlderThan(olderThan)
    }

    // TODO (E3-002): linkAliasToCanonical should return AliasLinkResult (Created/Updated/Conflict)
    // instead of Unit, so callers can distinguish new alias creation from update of existing alias.
    suspend fun linkAliasToCanonical(rawName: String, normalizedKey: String, canonicalId: Long, isUserDefined: Boolean = false, timestamp: Long) {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.linkAliasToCanonical")
        dao.linkAliasToCanonical(rawName, normalizedKey, canonicalId, isUserDefined, timestamp)
    }

    suspend fun getCanonicalCount(): Int =
        dao.getCanonicalCount()
}
