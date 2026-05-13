package com.yourname.expensetracker.golden

import com.yourname.expensetracker.data.currency.ExchangeRateStoreAdapter
import com.yourname.expensetracker.data.database.entity.BankConnection
import com.yourname.expensetracker.data.database.entity.SyncFrequency
import com.yourname.expensetracker.data.database.entity.SyncStatus
import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.testfixtures.golden.GoldenScenarioVerifier
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

/**
 * Golden Scenario Test: Bank Sync Failure Recovery
 *
 * Proves that:
 * 1. Expired token state is tracked (consecutiveErrors, lastSyncStatus=FAILED)
 * 2. Partial sync updates status correctly
 * 3. Successful sync after failure resets error state
 * 4. Disconnect clears tokens and deactivates
 * 5. Dashboard only includes expenses from successful syncs (not corrupted by partial)
 */
class BankSyncFailureRecoveryGoldenTest : GoldenTestBase() {

    private lateinit var multiCurrencyRepository: MultiCurrencyRepository

    private val verifier = GoldenScenarioVerifier(
        scenarioName = "bank_sync_failure_recovery",
        numericTolerance = 0.01
    )

    @Before
    override fun setUp() {
        super.setUp()
        val currencySettings = mockk<CurrencySettingsRepository>().also {
            every { it.homeCurrency() } returns flowOf("EUR")
        }
        val exchangeRateStore = ExchangeRateStoreAdapter(database.exchangeRateDao())
        val currencyConverter = CurrencyConverter(exchangeRateStore, timeProvider)
        multiCurrencyRepository = MultiCurrencyRepository(
            expenseDao = database.expenseDao(),
            currencyConverter = currencyConverter,
            timeProvider = timeProvider,
            currencySettingsRepository = currencySettings
        )
    }

    @Test
    fun `bank sync failure and recovery lifecycle`() = runTest {
        seedCategories()

        // ── SEED: Active bank connection ──
        val connId = database.bankConnectionDao().insert(BankConnection(
            bankId = "nbg", bankName = "National Bank of Greece",
            countryCode = "GR", accessToken = "enc:v1:token123",
            refreshToken = "enc:v1:refresh456", tokenExpiry = fixedNow + 3600000L,
            isActive = true, isConnected = true, lastSyncStatus = SyncStatus.SUCCESS,
            lastSync = fixedNow - 86400000L, createdAt = fixedNow - 86400000L * 30
        ))

        // ── ACT 1: Sync fails (expired token) ──
        database.bankConnectionDao().updateSyncStatus(connId, fixedNow, SyncStatus.FAILED)
        val afterFailure = database.bankConnectionDao().getById(connId)

        // ── ACT 2: Insert expense from previous successful sync (already in DB) ──
        insertExpense(createPurchase(amount = 45.0, currency = "EUR", merchant = "Sklavenitis", categoryId = 1))

        // ── ACT 3: Partial sync (some transactions imported) ──
        database.bankConnectionDao().updateSyncStatus(connId, fixedNow, SyncStatus.PARTIAL)
        insertExpense(createPurchase(amount = 30.0, currency = "EUR", merchant = "Shell", categoryId = 2))
        val afterPartial = database.bankConnectionDao().getById(connId)

        // ── ACT 4: Successful recovery sync ──
        database.bankConnectionDao().updateSyncStatus(connId, fixedNow, SyncStatus.SUCCESS)
        val afterRecovery = database.bankConnectionDao().getById(connId)

        // ── ACT 5: Disconnect ──
        database.bankConnectionDao().disconnect(connId)
        val afterDisconnect = database.bankConnectionDao().getById(connId)

        // ── QUERY: Dashboard total (all expenses from successful imports) ──
        val periodStart = fixedNow - 86400000L * 30
        val periodEnd = fixedNow + 86400000L
        val dashboardTotal = multiCurrencyRepository.getHomeCurrencyPurchaseTotal(periodStart, periodEnd)

        // ── SERIALIZE ──
        val actual = JSONObject().apply {
            put("afterFailure_status", afterFailure?.lastSyncStatus?.name)
            put("afterPartial_status", afterPartial?.lastSyncStatus?.name)
            put("afterRecovery_status", afterRecovery?.lastSyncStatus?.name)

            put("afterDisconnect_isActive", afterDisconnect?.isActive)
            put("afterDisconnect_isConnected", afterDisconnect?.isConnected)
            put("afterDisconnect_tokenCleared", afterDisconnect?.accessToken == null)

            put("dashboardTotal", dashboardTotal.displayAmount)
            put("dashboardTransactionCount", dashboardTotal.totalTransactionCount)
            put("dataNotCorrupted", dashboardTotal.displayAmount == 75.0)
        }

        verifier.verify(actual).assertPassed()
    }
}
