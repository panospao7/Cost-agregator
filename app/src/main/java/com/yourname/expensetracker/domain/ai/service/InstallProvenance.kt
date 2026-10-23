package com.yourname.expensetracker.domain.ai.service

/**
 * How this app installation came to exist on the device (ai-fixes B2b, spec 1.8).
 *
 * Used only by the AI settings DataStore migration (INV-23) to pick the one-time default of the
 * transaction-insight toggle. It is a platform signal, not a business rule, and never leaves the device.
 */
enum class InstallOrigin {
    /** First install of this app on the device and no update since. */
    NEW_INSTALL,

    /** The app was updated after its first install. */
    UPGRADE,

    /** The origin could not be determined. Callers must fail safe. */
    UNKNOWN
}

/**
 * Classifies the installation of this app from own-package install and update times only.
 *
 * Contract: [classify] never throws. Any failure is reported as [InstallOrigin.UNKNOWN].
 * Implementations must not need extra permissions, must not enumerate other packages and
 * must not use the network.
 */
interface InstallProvenance {
    fun classify(): InstallOrigin
}
