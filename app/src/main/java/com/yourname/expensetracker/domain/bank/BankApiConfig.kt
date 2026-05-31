package com.yourname.expensetracker.domain.bank

/**
 * Configuration for bank API integration rollout.
 *
 * P10-PR1 (NEW-P10-001): isStubMode is now immutable — derived from BuildConfig.DEBUG.
 * No runtime mutation possible; testability via DI override, not mutable global.
 */
object BankApiConfig {
    val isStubMode: Boolean = com.yourname.expensetracker.BuildConfig.DEBUG

    val isProduction: Boolean
        get() = !isStubMode
}
