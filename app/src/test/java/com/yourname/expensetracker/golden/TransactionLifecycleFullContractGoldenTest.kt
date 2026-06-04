package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.TransactionEvent
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Transaction Lifecycle Full Contract
 *
 * Proves the DB contract for expense CRUD:
 * 1. Create → expense persisted + CREATED event written
 * 2. Duplicate create → rejected by dedupeKey unique index + event written
 * 3. Update → expense modified + UPDATED event written
 * 4. Delete → expense removed + DELETED event written
 * 5. Dashboard total reflects each state correctly
 * 6. Event log is durable (survives expense deletion)
 *
 * Tests the contract that TransactionLifecycleCoordinator enforces,
 * without wiring all 10 coordinator dependencies.
 */
class TransactionLifecycleFullContractGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "transaction_lifecycle_full_contract",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()

        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
            coEvery { it.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
    }

    @Test
    fun `expense lifecycle - create update delete with events`() = runTest {
        seedCategories()
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L

        // ── CREATE: Insert expense with dedupeKey ──
        val expense = createPurchase(amount = 50.0, merchant = "Lidl", categoryId = 1)
            .copy(dedupeKey = "50.0_lidl_${fixedNow / 86400000}_EUR")
        val expenseId = database.expenseDao().insertAtomic(expense)

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expenseId, eventType = "CREATED",
            source = "MANUAL", actor = "user", occurredAt = fixedNow,
            dedupeKey = null, duplicateExpenseId = null,
            beforeSnapshot = null, afterSnapshot = null,
            metadata = null, reason = null
        ))

        val totalAfterCreate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── DUPLICATE: Same dedupeKey → rejected ──
        val dupExpense = createPurchase(amount = 50.0, merchant = "Lidl", categoryId = 1)
            .copy(dedupeKey = "50.0_lidl_${fixedNow / 86400000}_EUR")
        val dupResult = database.expenseDao().insertAtomic(dupExpense)

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = null, eventType = "CREATE_DUPLICATE_SKIPPED",
            source = "MANUAL", actor = "user", occurredAt = fixedNow,
            dedupeKey = "50.0_lidl_${fixedNow / 86400000}_EUR",
            duplicateExpenseId = expenseId, beforeSnapshot = null,
            afterSnapshot = null, metadata = null, reason = "dedupeKey conflict"
        ))

        val totalAfterDup = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── UPDATE: Change amount ──
        val updated = expense.copy(id = expenseId, amount = 75.0)
        database.expenseDao().update(updated)

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expenseId, eventType = "UPDATED",
            source = "USER_EDIT", actor = "user", occurredAt = fixedNow,
            dedupeKey = null, duplicateExpenseId = null,
            beforeSnapshot = """{"amount":50.0}""",
            afterSnapshot = """{"amount":75.0}""",
            metadata = null, reason = "amount changed"
        ))

        val totalAfterUpdate = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── DELETE: Remove expense ──
        database.expenseDao().delete(updated)

        database.transactionEventDao().insert(TransactionEvent(
            expenseId = expenseId, eventType = "DELETED",
            source = "USER_ACTION", actor = "user", occurredAt = fixedNow,
            dedupeKey = null, duplicateExpenseId = null,
            beforeSnapshot = """{"amount":75.0,"merchant":"Lidl"}""",
            afterSnapshot = null, metadata = null, reason = null
        ))

        val totalAfterDelete = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── QUERY: Events survive expense deletion ──
        val allEvents = database.transactionEventDao().getRecentEvents(50)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("createSucceeded", expenseId > 0)
            put("duplicateRejected", dupResult <= 0L)

            put("totalAfterCreate", totalAfterCreate.displayAmount)
            put("totalAfterDuplicate", totalAfterDup.displayAmount)
            put("totalAfterUpdate", totalAfterUpdate.displayAmount)
            put("totalAfterDelete", totalAfterDelete.displayAmount)

            put("duplicateDidNotChangeDashboard",
                Math.abs(totalAfterCreate.displayAmount - totalAfterDup.displayAmount) < 0.01)

            put("eventsSurviveExpenseDeletion", allEvents.size == 4)
            put("eventSequence", JSONArray().apply {
                allEvents.sortedBy { it.id }.forEach { put(it.eventType) }
            })
        }

        verifier.verify(actual).assertPassed()
    }
}
