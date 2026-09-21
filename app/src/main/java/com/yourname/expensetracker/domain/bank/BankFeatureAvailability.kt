package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RP-17 17-A (register D12 / D1): central availability policy for the bank-sync
 * product surface.
 *
 * D1 (reaffirmed): the bank-sync surface is UNAVAILABLE in release builds.
 * Debug builds (and any future real provider mode) are permitted. This single
 * policy is applied to every entry point:
 *  - FeatureConfig / Features menu
 *  - MainActivity navigation render branch
 *  - deep-link host parsing (bank hosts are recognized and rejected)
 *  - persisted destination restoration
 *  - [BankConnectionLifecycleCoordinator] public `initiateConnection`,
 *    `completeConnection`, and `syncConnection`
 *
 * Unavailable release behavior is a typed `FeatureUnavailable` outcome — it must
 * never silently retry, render an empty screen, or enter provider integration.
 */
@Singleton
class BankFeatureAvailability private constructor(
    private val debugMode: Boolean
) {
    /** Hilt entry point: production resolves availability from BuildConfig (D1). */
    @Inject constructor() : this(BuildConfig.DEBUG)

    /** D1: bank sync is available only in debug/provider mode. */
    val isBankSyncAvailable: Boolean
        get() = debugMode

    /** Typed availability result for callers that must branch on it. */
    fun availability(): BankAvailability =
        if (isBankSyncAvailable) BankAvailability.Available
        else BankAvailability.FeatureUnavailable(BankUnavailableReason.RELEASE_BUILD)

    companion object {
        /**
         * Deep-link hosts that refer to the bank-sync surface. There is
         * intentionally NO bank deep-link route today; this set future-proofs
         * the contract: adding a route requires consciously removing the host
         * from this rejection set and passing the availability gate.
         */
        val BANK_DEEP_LINK_HOSTS: Set<String> = setOf("bank", "banks", "bank_connections")

        fun isBankDeepLinkHost(host: String?): Boolean =
            host != null && host.lowercase() in BANK_DEEP_LINK_HOSTS

        /** Test seam: construct a policy simulating a debug (permitted) or release (gated) build. */
        fun forDebugMode(debugMode: Boolean): BankFeatureAvailability =
            BankFeatureAvailability(debugMode)
    }
}

/** Why the bank-sync surface is unavailable. Controlled constants only. */
enum class BankUnavailableReason {
    /** D1: bank-sync surface is disabled in release builds. */
    RELEASE_BUILD
}

/** Typed availability outcome (17-A: never a boolean-only signal). */
sealed interface BankAvailability {
    data object Available : BankAvailability
    data class FeatureUnavailable(val reason: BankUnavailableReason) : BankAvailability
}
