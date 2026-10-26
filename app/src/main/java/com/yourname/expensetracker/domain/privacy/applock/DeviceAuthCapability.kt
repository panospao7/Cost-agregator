package com.yourname.expensetracker.domain.privacy.applock

/**
 * Whether the device can run a strong-biometric-or-device-credential authentication right now
 * (domain-spec 2.3 `capability`, INV-23).
 *
 * The set is closed and bounded: it carries no platform codes or text. Only [AVAILABLE] allows the
 * lock to be enabled or a device-auth lock to stay active (T4, T26).
 */
enum class DeviceAuthCapability {
    /** API level below 30. The platform combination is not supported there (AE-34). */
    UNSUPPORTED_API,

    /** A strong biometric or the device credential can authenticate. */
    AVAILABLE,

    /** No screen lock and nothing enrolled (AE-35). */
    NO_CREDENTIAL,

    /** The hardware is missing or temporarily unusable. A retry re-evaluates (AE-93). */
    HW_UNAVAILABLE,

    /** Unsupported, status unknown, security update required, or the check itself failed. */
    UNKNOWN
}

/**
 * Port that reports the current [DeviceAuthCapability].
 *
 * Every call re-evaluates. Callers must not cache the result across user actions (INV-23, AMD-3).
 */
interface DeviceAuthCapabilityProvider {
    fun current(): DeviceAuthCapability
}
