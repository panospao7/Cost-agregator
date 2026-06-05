package com.yourname.expensetracker.domain.categorization

/**
 * Result of linking a merchant alias to a canonical merchant.
 */
sealed class AliasLinkResult {
    /** A new alias was created. */
    data class Created(val aliasId: Long) : AliasLinkResult()

    /** An existing alias for the same canonical was updated (occurrenceCount, lastUsedAt). */
    data class UpdatedExisting(val aliasId: Long) : AliasLinkResult()

    /** The normalized key is already linked to a different canonical. */
    data class Conflict(val existingCanonicalId: Long, val message: String) : AliasLinkResult()

    /** The target canonical does not exist. */
    data class CanonicalMissing(val canonicalId: Long) : AliasLinkResult()

    /** The operation was ignored (e.g., insert failed unexpectedly). */
    data class Ignored(val reason: String) : AliasLinkResult()
}
