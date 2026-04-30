package com.yourname.expensetracker.domain.savings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.AutomatedSavingsRuleStateRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.SavingsGoalRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

class AutomatedSavingsRuleEngineGoldenTest {

    private lateinit var engine: AutomatedSavingsRuleEngine
    private var stateScope: CoroutineScope? = null

    @Before
    fun setUp() {
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val savingsGoalRepository = mockk<SavingsGoalRepository>(relaxed = true)
        val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        val timeProvider = FakeTimeProvider(1_700_000_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        stateScope = scope
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { Files.createTempFile("automated-savings-rule-engine-golden", ".preferences_pb").toFile() }
        )
        val stateRepository = AutomatedSavingsRuleStateRepository(dataStore, timeProvider)

        engine = AutomatedSavingsRuleEngine(
            expenseRepository = expenseRepository,
            categoryRepository = categoryRepository,
            timeProvider = timeProvider,
            ruleStateRepository = stateRepository,
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @After
    fun tearDown() {
        stateScope?.cancel()
    }

    @Test
    fun `round up golden case 17 30 to nearest 5 produces savings amount 2 70`() = runTest {
        val expense = Expense(
            id = 1L,
            amount = 17.30,
            merchant = "Cafe",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L
        )
        val rule = AutomatedSavingsRule(
            id = 1L,
            name = "Round up to 5",
            ruleType = SavingsRuleType.ROUND_UP,
            targetGoalId = 42L,
            roundUpTo = 5.0,
            isActive = true
        )

        val executions = engine.evaluateRules(expense, listOf(rule))

        assertApproxEquals(1.0, executions.size.toDouble(), 0.0)
        assertApproxEquals(2.70, executions.first().amount, 0.01)
    }
}
