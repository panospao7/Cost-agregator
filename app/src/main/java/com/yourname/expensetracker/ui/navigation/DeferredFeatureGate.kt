package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.BuildConfig

/**
 * Release 1.0 (R19, "H-1 hide"): unfinished features that are hidden in RELEASE
 * builds and only reachable in DEBUG builds.
 *
 * Hidden surfaces: Tax configuration, Investment portfolio, Spending challenges.
 * Their data (tables, backup verifier entries) is intentionally kept untouched;
 * only the entry points are gated:
 *  - FeatureConfig / Features menu ([FeatureConfig.availableFeatures])
 *  - persisted destination restoration ([destinationFromSaveToken])
 *  - MainActivity render branch (redirects Home via [isDeferredDestination])
 *
 * There are no deep-link hosts, notifications or shortcuts for these features.
 */
object DeferredFeatureGate {

    /** FeatureConfig ids of the deferred features. */
    val DEFERRED_FEATURE_IDS: Set<String> = setOf("tax", "investment", "challenges")

    /** Save-token base names of the deferred destinations. */
    val DEFERRED_SAVE_TOKENS: Set<String> = setOf(
        "tax_configuration",
        "investment_portfolio",
        "spending_challenges"
    )

    /** Deferred features are available only in debug builds. */
    val available: Boolean = BuildConfig.DEBUG

    fun isDeferredFeatureId(featureId: String): Boolean = featureId in DEFERRED_FEATURE_IDS

    /** True when [destination] belongs to a deferred (hidden-in-release) feature. */
    fun isDeferredDestination(destination: NavigationDestination): Boolean =
        destination is NavigationDestination.TaxConfiguration ||
            destination is NavigationDestination.InvestmentPortfolio ||
            destination is NavigationDestination.SpendingChallenges

    /**
     * True when the render layer must redirect away from [destination]
     * (deferred destination while the deferred surface is unavailable).
     */
    fun shouldRedirectHome(
        destination: NavigationDestination,
        deferredAvailable: Boolean = available
    ): Boolean = !deferredAvailable && isDeferredDestination(destination)
}
