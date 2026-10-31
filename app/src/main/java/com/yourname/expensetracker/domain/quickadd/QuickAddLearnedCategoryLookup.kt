package com.yourname.expensetracker.domain.quickadd

import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/** Result of the learned merchant-to-category lookup (C2). Carries no merchant text or key. */
sealed interface LearnedCategoryLookupResult {
    /** A learned row exists and its category is in the picker list. */
    data class Found(val match: CategoryMatch) : LearnedCategoryLookupResult

    /** No usable learned category: null/blank key, no row, or the row's category is not in the picker. */
    data object Absent : LearnedCategoryLookupResult

    /** The read threw a non-cancellation exception. The caller logs a bounded code; nothing is attached. */
    data object Failed : LearnedCategoryLookupResult
}

/**
 * C2: read-only learned merchant-to-category lookup for quick-add (spec 4.11).
 *
 * Steps: `key = CategorizationEngine.normalizedCanonicalNameForMerchant(merchant)` (pure; the single
 * owner of key derivation), then `MerchantCategoryRepository.getCategoryByNormalizedCanonical(key)` (a
 * pure DAO read). A row is used only if its category id is in the picker list.
 *
 * Read-only by construction (INV-11): this class has no write path, never learns, never creates a
 * merchant canonical or alias, and never ranks, scores or traces a decision. It does not log (INV-13);
 * a failure is reported to the caller as [LearnedCategoryLookupResult.Failed] and the caller owns the
 * bounded diagnostic code. Cancellation is always rethrown.
 */
class QuickAddLearnedCategoryLookup @Inject constructor(
    private val categorizationEngine: CategorizationEngine,
    private val merchantCategoryRepository: MerchantCategoryRepository
) {

    suspend fun lookup(merchant: MerchantText, categories: List<CategoryRef>): LearnedCategoryLookupResult {
        if (categories.isEmpty()) return LearnedCategoryLookupResult.Absent
        return try {
            val key = categorizationEngine.normalizedCanonicalNameForMerchant(merchant.text)
            if (key.isBlank()) return LearnedCategoryLookupResult.Absent
            val row = merchantCategoryRepository.getCategoryByNormalizedCanonical(key)
                ?: return LearnedCategoryLookupResult.Absent
            if (categories.none { it.id == row.categoryId }) {
                LearnedCategoryLookupResult.Absent
            } else {
                LearnedCategoryLookupResult.Found(CategoryMatch(row.categoryId, CategorySource.LEARNED))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LearnedCategoryLookupResult.Failed
        }
    }
}
