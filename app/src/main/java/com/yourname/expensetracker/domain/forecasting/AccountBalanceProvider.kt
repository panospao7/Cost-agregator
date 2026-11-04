package com.yourname.expensetracker.domain.forecasting

/**
 * Abstraction for resolving the user's current account balance.
 *
 * Implementations:
 * - [NetCashflowBalanceProvider]: 90-day net cashflow estimate (fallback).
 * - Future: BankConnectionBalanceProvider, ManualBalanceProvider.
 */
interface AccountBalanceProvider {
    /**
     * Current balance in [currency], or null when it is unavailable (for example the currency
     * is not the home currency, or a read/conversion failed). Null is never to be treated as 0.
     */
    suspend fun currentBalance(currency: String): Double?
}
