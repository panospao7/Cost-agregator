package com.yourname.expensetracker.domain.forecasting

/**
 * Abstraction for resolving the user's current account balance.
 *
 * Implementations:
 * - [NetCashflowBalanceProvider]: 90-day net cashflow estimate (fallback).
 * - Future: BankConnectionBalanceProvider, ManualBalanceProvider.
 */
interface AccountBalanceProvider {
    suspend fun currentBalance(currency: String): Double?
}
