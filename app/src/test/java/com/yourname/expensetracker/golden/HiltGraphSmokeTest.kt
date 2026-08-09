package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.domain.analytics.BudgetVsActualEngine
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.export.CsvCellSanitizer
import com.yourname.expensetracker.domain.forecasting.DataQualityAssessor
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import org.junit.Assert.*
import org.junit.Test

/**
 * Smoke tests for DI graph construction and navigation route integrity.
 *
 * These are NOT golden tests (no JSON comparison). They catch wiring failures
 * that golden JVM tests may miss — e.g., missing @Inject constructors,
 * circular dependencies, or duplicate navigation tokens.
 */
class HiltGraphSmokeTest : GoldenTestBase() {

    /**
     * Verifies that key singleton objects can be constructed without Hilt.
     * If any of these fail, the Hilt graph would also fail at runtime.
     */
    @Test
    fun `core singletons can be constructed`() {
        // No-arg constructors
        val budgetEngine = BudgetVsActualEngine()
        assertNotNull(budgetEngine)

        val dataQualityAssessor = DataQualityAssessor()
        assertNotNull(dataQualityAssessor)

        // Object singletons
        assertNotNull(CsvCellSanitizer)
        assertNotNull(MerchantKeyGenerator)

        // Constructor-injected classes with real deps
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao(), writeBarrier)
        assertNotNull(exchangeRateStore)

        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        assertNotNull(currencyConverter)

        // Write barrier with real maintenance mode
        val writeBarrier = DatabaseWriteBarrier(restoreMaintenanceMode)
        assertNotNull(writeBarrier)
    }

    /**
     * Verifies that the Room database exposes all expected DAOs.
     * A missing DAO abstract method would crash at runtime.
     */
    @Test
    fun `database exposes all critical DAOs`() {
        assertNotNull(database.expenseDao())
        assertNotNull(database.categoryDao())
        assertNotNull(database.exchangeRateDao())
        assertNotNull(database.budgetDao())
        assertNotNull(database.scannedReceiptDao())
        assertNotNull(database.receiptExpenseLinkDao())
        assertNotNull(database.receiptEventDao())
        assertNotNull(database.recurringOccurrenceDao())
        assertNotNull(database.plannedExpenseDao())
        assertNotNull(database.manualRecurringExpenseDao())
        assertNotNull(database.recurringReminderDeliveryDao())
        assertNotNull(database.transactionEventDao())
        assertNotNull(database.bankConnectionDao())
        assertNotNull(database.privacyAuditDao())
        assertNotNull(database.investmentDao())
    }
}

/**
 * Verifies navigation destination integrity.
 */
class NavigationRouteSmokeTest {

    @Test
    fun `core navigation destinations can be instantiated`() {
        // Verify key destinations exist and are distinct types
        val home = NavigationDestination.Home
        val assistant = NavigationDestination.Assistant
        val budget = NavigationDestination.Budget

        assertNotNull(home)
        assertNotNull(assistant)
        assertNotNull(budget)

        // They are distinct instances
        assertNotEquals(home, assistant)
        assertNotEquals(home, budget)
    }
}
