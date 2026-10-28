package com.yourname.expensetracker.domain.privacy.applock

/** How INV-45 classifies a review-save record. Closed and bounded: no text. */
enum class ReviewSaveClass {
    /** At least one effective value is weaker than the fail-closed record `F`: needs one fresh authentication when possible. */
    WEAKENING,

    /** Every effective value equals `F` (or is stronger): no authentication. */
    NOT_WEAKENING
}

/**
 * INV-45 (review-save authorisation, S18 ii-d), as a pure function of the record. No I/O, no state, no clock.
 *
 * A record is weakening when any effective value it would produce (spec 2.3) is weaker than the fail-closed record
 * `F` = {lock on, `IMMEDIATE`, discreet on, secure screen on}:
 * - the lock is off, or
 * - the timeout is longer than [AutoLockTimeout.IMMEDIATE].
 *
 * Discreet and secure screen do not count: with the lock on they are FORCED on (INV-48), so a stored off value is
 * hidden by forcing; with the lock off the record is already weakening through the lock flag. So, for example,
 * {lock on, `IMMEDIATE`, discreet off, secure off} is NOT weakening (AE-86), and {lock off, anything} is.
 *
 * Whether a prompt is then needed, or none is possible (`INERT`, D3), or the record is rejected for the capability, is
 * decided by [AppLockSettingsOperations]; this class only classifies.
 */
object ReviewSaveAuthorisation {

    /** The class of [record] under INV-45. */
    fun classify(record: AppLockPrivacySettings): ReviewSaveClass =
        if (!record.appLockEnabled || record.autoLockTimeout.millis > AutoLockTimeout.IMMEDIATE.millis) {
            ReviewSaveClass.WEAKENING
        } else {
            ReviewSaveClass.NOT_WEAKENING
        }

    /** True when [classify] is [ReviewSaveClass.WEAKENING]. */
    fun isWeakening(record: AppLockPrivacySettings): Boolean = classify(record) == ReviewSaveClass.WEAKENING
}
