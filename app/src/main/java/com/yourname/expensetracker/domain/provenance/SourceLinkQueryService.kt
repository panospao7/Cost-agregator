package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao
import com.yourname.expensetracker.data.database.entity.EntitySourceLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PR9: Query service for source link provenance data.
 *
 * Provides read-only access to source link data for UI display,
 * debug tools, and export pipelines.
 */
@Singleton
class SourceLinkQueryService @Inject constructor(
    private val sourceLinkDao: EntitySourceLinkDao
) {

    /**
     * Returns all source links for a given expense, ordered by primary first.
     */
    suspend fun getLinksForExpense(expenseId: Long): List<EntitySourceLink> {
        return sourceLinkDao.getForExpense(expenseId)
    }

    /**
     * Returns all source links for a given target entity type and ID.
     */
    suspend fun getLinksForTarget(
        targetType: TargetEntityType,
        targetId: Long
    ): List<EntitySourceLink> {
        return sourceLinkDao.getForTarget(targetType.name, targetId)
    }

    /**
     * Finds all target entities linked to a given source identity key.
     */
    suspend fun getTargetsForSource(sourceIdentityKey: String): List<EntitySourceLink> {
        return sourceLinkDao.getBySourceIdentityKey(sourceIdentityKey)
    }

    /**
     * Returns a human-readable summary of source links for display.
     */
    suspend fun getExpenseSourceSummary(expenseId: Long): String {
        val links = getLinksForExpense(expenseId)
        if (links.isEmpty()) return "No provenance data"

        val primary = links.find { it.isPrimary }
        val primaryDesc = primary?.let {
            "${it.sourceType} (${it.linkRole})"
        } ?: links.first().let {
            "${it.sourceType} (${it.linkRole})"
        }

        val additional = links.filter { !it.isPrimary }.map { it.sourceType }
        return if (additional.isEmpty()) {
            primaryDesc
        } else {
            "$primaryDesc + ${additional.joinToString(", ")}"
        }
    }
}
