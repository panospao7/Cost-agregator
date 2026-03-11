package com.yourname.expensetracker.domain.util

import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer

/**
 * Single canonical merchant key generator.
 *
 * Produces a deterministic, case-folded, ASCII-only key from any merchant
 * display name (Greek, Latin, or mixed).  The same key is used by every
 * layer that needs to identify or compare merchants:
 *
 *  - [com.yourname.expensetracker.data.database.entity.Expense.merchantKey]
 *    (stored in the DB, indexed)
 *  - [com.yourname.expensetracker.data.database.entity.Expense.generateDedupeKey]
 *  - [com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer.createSearchKey]
 *  - [com.yourname.expensetracker.data.repository.MerchantLocationRepository.normalizeKey]
 *  - [com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao.linkAliasToCanonical]
 *
 * Algorithm (in order):
 *  1. Transliterate Greek → Latin using the diphthong-aware pipeline from
 *     [GreeklishNormalizer.toLatinStatic] (so "μπ" → "b", "ου" → "ou", etc.)
 *  2. Lowercase the result
 *  3. Strip every character that is not [a-z0-9]
 *
 * No length cap is applied — truncation caused collisions between distinct
 * merchants and has been removed.
 *
 * This object is dependency-free (no Hilt, no Context) so it can be called
 * from entity companion objects, DAOs, and anywhere else without injection.
 */
object MerchantKeyGenerator {

    /**
     * Generate the canonical merchant key for [merchantName].
     *
     * Examples:
     * - "Σκλαβενίτης"  → "sklavenitis"
     * - "Μπάρμπα Σταθης" → "barbasathis"  (μπ → b, diphthong-aware)
     * - "McDonald's"   → "mcdonalds"
     * - "LIDL"         → "lidl"
     * - ""             → ""
     */
    fun generate(merchantName: String): String {
        if (merchantName.isBlank()) return ""
        val latin = GreeklishNormalizer.toLatinStatic(merchantName)
        return latin.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
    }
}
