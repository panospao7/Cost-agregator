package com.yourname.expensetracker.domain.privacy.applock

/**
 * The one notice the controller may derive for an open state (domain-spec INV-51, AMD-6).
 *
 * Priority is [Inert] over [ReviewSettings] over [BiometricInvalidated]; a lower-priority notice waits,
 * it is not dropped. Notices carry no amounts, counts or link hints (INV-35). This is a type only: the
 * derivation belongs to the settings-operations slice (S18) and the renderer to the gate (S20).
 */
sealed interface LockNotice {
    /** The lock is enabled but cannot authenticate. Cannot be dismissed. */
    data class Inert(val reason: InertReason) : LockNotice

    /** The settings were unreadable in a persistent way and need a review-save. Cannot be dismissed. */
    data object ReviewSettings : LockNotice

    /** [2] Fingerprint unlock was turned off because the enrolment changed. Can be dismissed. */
    data object BiometricInvalidated : LockNotice
}
