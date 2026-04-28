package com.yourname.expensetracker.domain.currency

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection

/**
 * Canonical multi-currency test fixture used across dashboard, budget, analytics,
 * and forecast tests to verify currency-aware aggregation.
 *
 * Scenario:
 *   Expense A:  50 EUR  (effectiveAmount = 50.0,  currency = "EUR")
 *   Expense B: 100 USD  (effectiveAmount = 100.0, currency = "USD")
 *   Exchange rate: 1 USD = 0.92 EUR
 *
 * Expected results:
 *   - Currency-aware total in EUR: 50 + (100 × 0.92) = 142 EUR
 *   - Raw wrong total (mixed currency): 50 + 100 = 150 (INCORRECT, must never appear)
 *   - Per-currency buckets: {EUR=50.0, USD=100.0}
 */
object MultiCurrencyTestFixture {

    const val EUR_AMOUNT = 50.0
    const val USD_AMOUNT = 100.0
    const val USD_TO_EUR_RATE = 0.92
    const val EXPECTED_EUR_TOTAL = EUR_AMOUNT + (USD_AMOUNT * USD_TO_EUR_RATE) // 142.0
    const val WRONG_RAW_TOTAL = EUR_AMOUNT + USD_AMOUNT // 150.0 — must NEVER appear

    const val EUR_CURRENCY = "EUR"
    const val USD_CURRENCY = "USD"
    const val HOME_CURRENCY = EUR_CURRENCY

    /** Reference date range for tests (1-day window). */
    const val START_DATE = 1700000000000L
    const val END_DATE   = 1700086400000L // +24h

    /**
     * Create EUR test expense.
     * Uses default values matching the Expense entity schema.
     */
    fun eurExpense(
        id: Long = 1L,
        date: Long = START_DATE
    ): Expense = Expense(
        id = id,
        amount = EUR_AMOUNT,
        currency = EUR_CURRENCY,
        merchant = "Test Merchant EUR",
        transactionType = TransactionType.PURCHASE,
        date = date,
        rawNotificationId = null,
        categoryId = null,
        createdAt = date,
        paymentMethod = PaymentMethod.CARD,
        isManualEntry = false,
        notes = null,
        dedupeKey = "test-eur-$id",
        transferDirection = null,
        transferAccountName = null,
        isNotMine = false,
        ownerName = null,
        isSharedExpense = false,
        sharedWithName = null,
        mySharePercentage = null,
        myShareAmount = null,
        latitude = null,
        longitude = null,
        locationSource = null,
        placeId = null,
        backfillAttempts = 0,
        resolvedAddress = null,
        merchantKey = "test-merchant-eur",
        isBusinessExpense = false,
        businessPurpose = null,
        businessCategory = null,
        businessProject = null,
        requiresReceipt = false,
        splitTemplateId = null,
        splitVisualization = null
    )

    /**
     * Create USD test expense.
     * Uses default values matching the Expense entity schema.
     */
    fun usdExpense(
        id: Long = 2L,
        date: Long = START_DATE
    ): Expense = Expense(
        id = id,
        amount = USD_AMOUNT,
        currency = USD_CURRENCY,
        merchant = "Test Merchant USD",
        transactionType = TransactionType.PURCHASE,
        date = date,
        rawNotificationId = null,
        categoryId = null,
        createdAt = date,
        paymentMethod = PaymentMethod.CARD,
        isManualEntry = false,
        notes = null,
        dedupeKey = "test-usd-$id",
        transferDirection = null,
        transferAccountName = null,
        isNotMine = false,
        ownerName = null,
        isSharedExpense = false,
        sharedWithName = null,
        mySharePercentage = null,
        myShareAmount = null,
        latitude = null,
        longitude = null,
        locationSource = null,
        placeId = null,
        backfillAttempts = 0,
        resolvedAddress = null,
        merchantKey = "test-merchant-usd",
        isBusinessExpense = false,
        businessPurpose = null,
        businessCategory = null,
        businessProject = null,
        requiresReceipt = false,
        splitTemplateId = null,
        splitVisualization = null
    )

    /**
     * Both expenses as a list.
     */
    fun allExpenses(date: Long = START_DATE): List<Expense> = listOf(
        eurExpense(date = date),
        usdExpense(date = date)
    )

    /**
     * Amounts paired with their currency, ready for CurrencyConverter.convertMultiple().
     */
    fun amountsWithCurrencies(): List<Pair<Double, String>> = listOf(
        EUR_AMOUNT to EUR_CURRENCY,
        USD_AMOUNT to USD_CURRENCY
    )

    /**
     * Per-currency bucket map.
     */
    fun currencyBuckets(): Map<String, Double> = mapOf(
        EUR_CURRENCY to EUR_AMOUNT,
        USD_CURRENCY to USD_AMOUNT
    )
}
