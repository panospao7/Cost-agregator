package com.yourname.expensetracker.domain.backup

/**
 * Outcome of the restore-time semantic (aggregate fingerprint) verification (UF-46, INV-11 / INV-15).
 *
 * There is deliberately no "failed" member: a mismatch, a malformed section or a query failure never
 * produces a summary. It blocks the restore before the swap or rolls it back after it, so the only
 * outcomes a successful restore can carry are [Verified] and [NotVerifiable].
 *
 * [Verified] means every one of the four aggregates matched at every checkpoint. Anything less
 * (legacy backup, unknown version, capture degraded, definition changed, no manifest) is
 * [NotVerifiable] and never [Verified].
 *
 * This type is domain-local and bounded: it carries no amounts, currencies or aggregate contents.
 * The data layer maps its own `SemanticCode` onto [SemanticNotVerifiableReason]; the domain layer
 * never imports the data-layer code.
 */
sealed interface SemanticVerificationOutcome {

    /** All four aggregates matched at C1, C2 and C3. */
    data object Verified : SemanticVerificationOutcome

    /** The restore proceeded under every existing gate, but the content could not be fingerprinted. */
    data class NotVerifiable(val reason: SemanticNotVerifiableReason) : SemanticVerificationOutcome
}

/** Bounded reasons a restore is not semantically verifiable (INV-11). */
enum class SemanticNotVerifiableReason {
    /** The manifest carries no semantic section (backup made before this feature, or section omitted). */
    LEGACY,

    /** Unknown section version, or a source schema older than the aggregate definitions support. */
    VERSION,

    /** The section exists but capture degraded at backup time (including an unknown or missing reason). */
    NOT_CAPTURED,

    /** An aggregate definition hash differs from the current definition. */
    DEFINITION_CHANGED,

    /** A raw `.db` import: there is no manifest to compare against. */
    NO_MANIFEST
}
