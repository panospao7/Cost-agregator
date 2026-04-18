package com.yourname.expensetracker.domain.bank

/**
 * Configuration for bank API integration rollout.
 *
 * Stub mode stays enabled by default until real provider integrations are implemented.
 */
object BankApiConfig {
    @Volatile
    var isStubMode: Boolean = true

    val isProduction: Boolean
        get() = !isStubMode
}
