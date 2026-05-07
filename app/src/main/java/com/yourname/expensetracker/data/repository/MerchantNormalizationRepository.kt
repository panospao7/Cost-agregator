package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantNormalizationRepository @Inject constructor(
    private val dao: MerchantNormalizationDao
) {
    suspend fun insertCanonical(merchant: MerchantCanonical): Long =
        dao.insertCanonical(merchant)

    suspend fun updateCanonical(merchant: MerchantCanonical) =
        dao.updateCanonical(merchant)

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

    suspend fun updateCanonicalCategory(id: Long, categoryId: Long?) =
        dao.updateCanonicalCategory(id, categoryId)

    // TODO (C08): incrementMerchantStats is never called — wire it from TransactionSideEffectDispatcher
    // after committed expense creation/update.
    suspend fun incrementMerchantStats(id: Long, amount: Double, timestamp: Long) =
        dao.incrementMerchantStats(id, amount, timestamp)

    suspend fun insertAlias(alias: MerchantAlias): Long =
        dao.insertAlias(alias)

    suspend fun updateAlias(alias: MerchantAlias) =
        dao.updateAlias(alias)

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

    suspend fun deleteUnusedAliasesOlderThan(olderThan: Long): Int =
        dao.deleteUnusedAliasesOlderThan(olderThan)

    suspend fun linkAliasToCanonical(rawName: String, normalizedKey: String, canonicalId: Long, isUserDefined: Boolean = false, timestamp: Long) =
        dao.linkAliasToCanonical(rawName, normalizedKey, canonicalId, isUserDefined, timestamp)

    suspend fun getCanonicalCount(): Int =
        dao.getCanonicalCount()
}
