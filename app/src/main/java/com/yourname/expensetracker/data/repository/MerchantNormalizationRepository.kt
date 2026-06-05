package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.categorization.AliasLinkResult
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class MerchantNormalizationRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: MerchantNormalizationDao,
    private val timeProvider: TimeProvider
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
        val safeAlias = if (alias.createdAt <= 0L || alias.lastUsedAt <= 0L) {
            val now = timeProvider.now()
            alias.copy(createdAt = now, lastUsedAt = now)
        } else alias
        val id = dao.insertAlias(safeAlias)
        if (id <= 0L) {
            writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.insertAlias fallback")
            // Try normalizedKey first (E3-001 fallback)
            val updated = dao.incrementAliasOccurrence(safeAlias.normalizedKey, safeAlias.lastUsedAt)
            if (updated != null) return updated.id

            // Fallback: try rawName conflict (same rawName, different normalizedKey)
            // Note: getAliasByRawName + updateAlias are separate calls, not wrapped in a
            // @Transaction. This is a small TOCTOU window. The primary path is
            // linkAliasToCanonical which is fully atomic. This fallback is only hit on
            // direct insertAlias calls with a rawName conflict, which is rare.
            val existingByRaw = dao.getAliasByRawName(safeAlias.rawName)
            if (existingByRaw != null) {
                dao.updateAlias(existingByRaw.copy(
                    occurrenceCount = existingByRaw.occurrenceCount + 1,
                    lastUsedAt = safeAlias.lastUsedAt
                ))
                return existingByRaw.id
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

    suspend fun linkAliasToCanonical(rawName: String, normalizedKey: String, canonicalId: Long, isUserDefined: Boolean = false, timestamp: Long): AliasLinkResult {
        writeBarrier.checkWritesAllowed("MerchantNormalizationRepository.linkAliasToCanonical")
        val resultCode = dao.linkAliasToCanonical(rawName, normalizedKey, canonicalId, isUserDefined, timestamp)
        return when (resultCode) {
            0 -> {
                val alias = dao.getAliasByNormalizedKey(normalizedKey)
                if (alias != null) AliasLinkResult.Created(alias.id)
                else AliasLinkResult.Ignored("Created but alias not found by normalizedKey")
            }
            1 -> {
                val alias = dao.getAliasByNormalizedKey(normalizedKey)
                if (alias != null) AliasLinkResult.UpdatedExisting(alias.id)
                else AliasLinkResult.Ignored("Updated but alias not found by normalizedKey")
            }
            2 -> {
                val existing = dao.getAliasByNormalizedKey(normalizedKey)
                // If the alias was deleted between the DAO transaction and this read,
                // existingCanonicalId falls back to -1. Callers should treat -1 as
                // "unknown conflicting canonical" and not use it as a valid DB key.
                AliasLinkResult.Conflict(
                    existing?.canonicalId ?: -1,
                    "Alias with normalized key '$normalizedKey' already linked to a different canonical"
                )
            }
            3 -> AliasLinkResult.CanonicalMissing(canonicalId)
            else -> AliasLinkResult.Ignored("Unknown result code: $resultCode")
        }
    }

    suspend fun getCanonicalCount(): Int =
        dao.getCanonicalCount()
}
