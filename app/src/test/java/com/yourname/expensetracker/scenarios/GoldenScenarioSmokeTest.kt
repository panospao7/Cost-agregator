package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.scenario.ScenarioAssertions
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeed
import com.yourname.expensetracker.testfixtures.scenario.ScenarioSeeder
import com.yourname.expensetracker.testfixtures.dateMs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden scenario smoke test that proves the scenario infrastructure
 * (AppDatabaseTestFactory, ScenarioSeeder, ScenarioAssertions) works
 * end-to-end by loading seed data and verifying the resulting DB state.
 *
 * This is intentionally a single test — it serves as a canary for
 * regressions in the test fixture layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenScenarioSmokeTest {

    private lateinit var context: Context
    private lateinit var db: com.yourname.expensetracker.data.database.AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `scenario infrastructure loads and seeds correctly`() = runTest {
        // GIVEN: seed data with categories and expenses
        val seed = ScenarioSeed(
            categories = listOf(
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Food"),
                com.yourname.expensetracker.testfixtures.scenario.CategorySeed("Transport")
            ),
            expenses = listOf(
                com.yourname.expensetracker.testfixtures.scenario.ExpenseSeed(
                    amount = 50.0,
                    currency = "EUR",
                    merchant = "Test",
                    date = dateMs(2026, 5, 1)
                )
            )
        )

        // WHEN: seeding the database
        val result = ScenarioSeeder(db).seedState(seed)

        // THEN: the expense is inserted and count matches
        with(ScenarioAssertions) {
            db.assertExpenseCount(1)
        }
    }
}
