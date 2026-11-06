package com.yourname.expensetracker.ui.screens.cashflow

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.cashflow.CashFlowRiskLevel
import com.yourname.expensetracker.domain.cashflow.DailyCashFlow
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.util.CurrencyFormatter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Date

/**
 * fix-1.0.4 (owner-approved revalidation fix, item 143): the Cash Flow calendar day-detail rows list
 * the user's own share (a shared row shows its share, not the full charge), hide rows the day totals
 * count as zero (not-mine, spending-excluded), and append a "+N more" line for rows beyond three, where
 * N counts only the listed-eligible rows. Rendered through the real screen with a stubbed view model
 * (the detail composable is private); all data is synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CashFlowCalendarDayDetailTest {

    /** ComponentActivity lives in ui-test-manifest only; register it so the compose rule can launch it. */
    private val registerActivityRule = object : ExternalResource() {
        override fun before() {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            shadowOf(appContext.packageManager)
                .addActivityIfNotPresent(ComponentName(appContext, ComponentActivity::class.java))
        }
    }

    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    private val dayMs = Instant.parse("2026-03-15T12:00:00Z").toEpochMilli()

    private var nextId = 1L

    private fun expense(
        merchant: String,
        amount: Double,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        exclusion: SpendingExclusionReason? = null
    ) = Expense(
        id = nextId++,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = type,
        date = dayMs,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        myShareAmount = myShareAmount,
        spendingExclusionReason = exclusion?.name
    )

    private fun render(income: List<Expense> = emptyList(), expenses: List<Expense> = emptyList()) {
        val day = DailyCashFlow(
            date = Date(dayMs),
            startingBalance = 0.0,
            income = income,
            expenses = expenses,
            predictedRecurring = emptyList(),
            endingBalance = 0.0,
            riskLevel = CashFlowRiskLevel.NONE,
            currency = "EUR"
        )
        val state = CashFlowCalendarState(
            dailyCashFlows = listOf(day),
            currentMonth = Date(dayMs),
            selectedDate = Date(dayMs),
            homeCurrency = "EUR"
        )
        val viewModel = mockk<CashFlowCalendarViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(state)
        composeRule.setContent { CashFlowCalendarScreen(onNavigateBack = {}, viewModel = viewModel) }
    }

    private fun expenseRow(merchant: String, amount: Double) =
        "• $merchant: -" + CurrencyFormatter.formatMoney(amount, "EUR")

    @Test
    fun `a shared expense row shows the user share not the full charge`() {
        render(
            expenses = listOf(
                expense("Shared dinner", amount = 100.0, isShared = true, myShareAmount = 25.0),
                expense("Plain coffee", amount = 3.0)
            )
        )

        composeRule.onNodeWithText(expenseRow("Shared dinner", 25.0)).assertExists()
        composeRule.onAllNodesWithText(expenseRow("Shared dinner", 100.0)).assertCountEquals(0)
        composeRule.onNodeWithText(expenseRow("Plain coffee", 3.0)).assertExists()
    }

    @Test
    fun `not-mine and spending-excluded rows are hidden from the day detail`() {
        render(
            expenses = listOf(
                expense("Visible shop", amount = 10.0),
                expense("Someone elses charge", amount = 40.0, isNotMine = true),
                expense("Excluded installment purchase", amount = 500.0, exclusion = SpendingExclusionReason.INSTALLMENT_PURCHASE)
            )
        )

        composeRule.onNodeWithText(expenseRow("Visible shop", 10.0)).assertExists()
        composeRule.onAllNodesWithText("Someone elses charge", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Excluded installment purchase", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a not-mine income row is hidden while a normal income row is listed`() {
        render(
            income = listOf(
                expense("Synthetic salary", amount = 2000.0, type = TransactionType.DEPOSIT),
                expense("Not mine deposit", amount = 70.0, type = TransactionType.DEPOSIT, isNotMine = true)
            )
        )

        composeRule.onNodeWithText("• Synthetic salary: +" + CurrencyFormatter.formatMoney(2000.0, "EUR")).assertExists()
        composeRule.onAllNodesWithText("Not mine deposit", substring = true).assertCountEquals(0)
    }

    @Test
    fun `five visible rows list three and show plus 2 more`() {
        render(expenses = (1..5).map { expense("Item $it", amount = it * 1.0) })

        composeRule.onNodeWithText(expenseRow("Item 1", 1.0)).assertExists()
        composeRule.onNodeWithText(expenseRow("Item 3", 3.0)).assertExists()
        composeRule.onAllNodesWithText("Item 4", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("+2 more").assertExists()
    }

    @Test
    fun `four visible rows show plus 1 more with the singular quantity`() {
        render(expenses = (1..4).map { expense("Item $it", amount = it * 1.0) })

        composeRule.onNodeWithText("+1 more").assertExists()
    }

    @Test
    fun `exactly three visible rows show no more line`() {
        render(expenses = (1..3).map { expense("Item $it", amount = it * 1.0) })

        composeRule.onNodeWithText(expenseRow("Item 3", 3.0)).assertExists()
        for (hidden in 0..3) {
            composeRule.onAllNodesWithText("+$hidden more").assertCountEquals(0)
        }
    }

    @Test
    fun `hidden not-mine rows do not inflate the more count`() {
        // 4 countable rows + 2 zero-counting rows: only the countable overflow (1) is reported.
        render(
            expenses = listOf(
                expense("Item 1", amount = 1.0),
                expense("Not mine A", amount = 9.0, isNotMine = true),
                expense("Item 2", amount = 2.0),
                expense("Not mine B", amount = 9.0, isNotMine = true),
                expense("Item 3", amount = 3.0),
                expense("Item 4", amount = 4.0)
            )
        )

        composeRule.onNodeWithText("+1 more").assertExists()
        composeRule.onAllNodesWithText("+3 more").assertCountEquals(0)
        composeRule.onAllNodesWithText("+2 more").assertCountEquals(0)
    }
}
