package com.yourname.expensetracker.ui.navigation

/**
 * Compile-time gate of the installments and loans feature (loan-installments S10a).
 *
 * While [ENABLED] is false the feature stays dormant:
 *  - the Features menu entry is withheld ([FeatureConfig.availableFeatures]);
 *  - the installment destinations are never rendered (MainActivity redirects Home);
 *  - the `installments` and `installment_plan` deep-link tokens are rejected
 *    (`DeepLinkPolicy.evaluate(installmentsEnabled = ENABLED)` returns UNKNOWN_HOST for them).
 *
 * Loan S13-a: the manifest hosts, the policy branches and the post-unlock confirmation dialog now exist, and are
 * gated only by the policy's `installmentsEnabled` parameter. The flag itself is flipped only by the enablement
 * slice (S13-b).
 */
object InstallmentsFeature {
    const val ENABLED: Boolean = false

    /** Stable id of the Features menu entry. */
    const val FEATURE_ID: String = "installments"

    /** Deep-link host of the overview: `expensetracker://installments`. */
    const val DEEP_LINK_HOST_OVERVIEW: String = "installments"

    /** Deep-link host of one plan: `expensetracker://installment_plan?id=<planId>`. */
    const val DEEP_LINK_HOST_PLAN: String = "installment_plan"

    /** True when [destination] belongs to the installments feature. */
    fun isInstallmentDestination(destination: NavigationDestination): Boolean =
        destination is NavigationDestination.Installments ||
            destination is NavigationDestination.InstallmentPlanDetail ||
            destination is NavigationDestination.InstallmentHistory

    /** True when the render layer must redirect away from [destination] (feature disabled). */
    fun shouldRedirectHome(
        destination: NavigationDestination,
        enabled: Boolean = ENABLED
    ): Boolean = !enabled && isInstallmentDestination(destination)
}
