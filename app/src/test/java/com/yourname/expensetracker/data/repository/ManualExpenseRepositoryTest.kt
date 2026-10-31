package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ml.ClassificationResult
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.RateRefusal
import com.yourname.expensetracker.domain.transaction.RateRefusalCodes
import com.yourname.expensetracker.domain.transaction.RateRefusalReason
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/**
 * MEC A2 (INV-14, INV-16, AE-26, G2-8): the manual-entry repository takes its amount cap from
 * [com.yourname.expensetracker.domain.currency.CurrencyAmountPolicy], rejects recurrence for a
 * currency that differs from the resolved home before the coordinator is called. B1 (setter #1):
 * every save carries the user-present rate flag with the caller's entry zone, and a flagged
 * refusal surfaces only as a bounded code.
 */
class ManualExpenseRepositoryTest {

    private val now = 1_712_000_000_000L
    private lateinit var database: AppDatabase
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private lateinit var recurringExpenseRepository: RecurringExpenseRepository
    private lateinit var currencySettingsRepository: CurrencySettingsRepository
    private lateinit var repository: ManualExpenseRepository
    private lateinit var merchantNormalizer: MerchantNormalizer
    private lateinit var hybridClassifier: HybridExpenseClassifier
    private val requestSlot = slot<CreateExpenseRequest>()

    @Before
    fun setUp() {
        database = mockk(relaxed = true)
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        coordinator = mockk(relaxed = true)
        coEvery { coordinator.createExpenseDbOnlyV2(capture(requestSlot)) } returns
            MutationResult(CreateExpenseResult.Created(7L), PostCommitActionBatch.empty("test"))
        recurringExpenseRepository = mockk(relaxed = true)
        coEvery { recurringExpenseRepository.insert(any()) } returns 5L
        currencySettingsRepository = mockk(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode.EUR)
        val expenseDao: ExpenseDao = mockk(relaxed = true)
        coEvery { expenseDao.getById(any()) } returns mockk<Expense>(relaxed = true)
        val timeProvider: TimeProvider = mockk()
        every { timeProvider.now() } returns now
        merchantNormalizer = mockk(relaxed = true)
        hybridClassifier = mockk(relaxed = true)

        repository = ManualExpenseRepository(
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            context = mockk<Context>(relaxed = true),
            database = database,
            expenseDao = expenseDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            categorizationEngine = mockk<CategorizationEngine>(relaxed = true),
            timeProvider = timeProvider,
            aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true),
            generateTransactionInsightUseCase = mockk<GenerateTransactionInsightUseCase>(relaxed = true),
            dashboardFollowThroughEngine = mockk<DashboardFollowThroughEngine>(relaxed = true),
            recommendationRepository = mockk<RecommendationRepository>(relaxed = true),
            transactionLifecycleCoordinator = coordinator,
            postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true),
            recurringExpenseRepository = recurringExpenseRepository,
            // Cancelled scope: the fire-and-forget recommendation job never runs.
            applicationScope = CoroutineScope(Job().apply { cancel() }),
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    private suspend fun add(
        amount: Double,
        currency: String,
        recurrence: RecurrenceFrequency? = null,
        zone: ZoneId = athens
    ): Result<Long> = repository.addManualExpense(
        merchant = "Cafe",
        amount = amount,
        currency = currency,
        categoryId = 1L,
        date = now,
        recurrenceFrequency = recurrence,
        entryZone = zone
    )

    // ── INV-16 / AE-26 ───────────────────────────────────────────────────────

    @Test
    fun `AE-26 USD with monthly recurrence is rejected with a bounded error before the coordinator`() =
        runTest(timeout = 60.seconds) {
            val result = add(20.0, "USD", RecurrenceFrequency.MONTHLY)

            assertTrue("Expected Error, got $result", result is Result.Error)
            assertEquals(
                ManualExpenseRepository.RECURRING_FOREIGN_CURRENCY_ERROR_CODE,
                (result as Result.Error).message
            )
            coVerify(exactly = 0) { coordinator.createExpenseDbOnlyV2(any()) }
            coVerify(exactly = 0) { recurringExpenseRepository.insert(any()) }
        }

    @Test
    fun `AE-26 EUR with monthly recurrence is unchanged`() = runTest(timeout = 60.seconds) {
        val result = add(20.0, "EUR", RecurrenceFrequency.MONTHLY)

        assertTrue("Expected Success, got $result", result is Result.Success)
        coVerify(exactly = 1) { coordinator.createExpenseDbOnlyV2(any()) }
        coVerify(exactly = 1) { recurringExpenseRepository.insert(any()) }
    }

    @Test
    fun `USD without recurrence is not affected by the recurrence rule`() = runTest(timeout = 60.seconds) {
        val result = add(20.0, "USD")

        assertTrue("Expected Success, got $result", result is Result.Success)
        coVerify(exactly = 1) { coordinator.createExpenseDbOnlyV2(any()) }
        coVerify(exactly = 0) { recurringExpenseRepository.insert(any()) }
    }

    @Test
    fun `unresolved home does not trigger the repository recurrence rejection`() = runTest(timeout = 60.seconds) {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Failed("Timeout")

        val result = add(20.0, "USD", RecurrenceFrequency.MONTHLY)

        assertTrue("Expected Success, got $result", result is Result.Success)
        coVerify(exactly = 1) { coordinator.createExpenseDbOnlyV2(any()) }
    }

    // ── INV-14: cap via CurrencyAmountPolicy ─────────────────────────────────

    @Test
    fun `KRW 1,500,000 passes the per-currency cap`() = runTest(timeout = 60.seconds) {
        val result = add(1_500_000.0, "KRW")

        assertTrue("Expected Success, got $result", result is Result.Success)
        coVerify(exactly = 1) { coordinator.createExpenseDbOnlyV2(any()) }
    }

    @Test
    fun `EUR above 1,000,000 and KRW above one billion are rejected before the coordinator`() =
        runTest(timeout = 60.seconds) {
            assertTrue(add(1_000_000.01, "EUR") is Result.Error)
            assertTrue(add(1_000_000_001.0, "KRW") is Result.Error)
            assertTrue(add(1_000_000.0, "USD") is Result.Success)
            coVerify(exactly = 1) { coordinator.createExpenseDbOnlyV2(any()) }
        }

    @Test
    fun `non-catalog currency keeps the 1,000,000 cap`() = runTest(timeout = 60.seconds) {
        assertTrue(add(1_000_000.5, "XYZ") is Result.Error)
        coVerify(exactly = 0) { coordinator.createExpenseDbOnlyV2(any()) }
    }

    // ── B1: setter #1 (inverted from the A2 "no flag" pin) ───────────────────

    @Test
    fun `B1 the request sent to the coordinator carries the user-present rate flag with the passed zone`() =
        runTest(timeout = 60.seconds) {
            val zone = ZoneId.of("Pacific/Auckland")

            add(20.0, "USD", zone = zone)

            assertEquals(UserPresentRateRequirement(zone), requestSlot.captured.userPresentRate)
        }

    @Test
    fun `B1 the flag follows each call's own zone`() = runTest(timeout = 60.seconds) {
        add(20.0, "EUR", zone = ZoneId.of("America/Los_Angeles"))
        assertEquals(
            UserPresentRateRequirement(ZoneId.of("America/Los_Angeles")),
            requestSlot.captured.userPresentRate
        )

        add(20.0, "EUR", zone = ZoneOffset.UTC)
        assertEquals(UserPresentRateRequirement(ZoneOffset.UTC), requestSlot.captured.userPresentRate)
    }

    // ── B1: typed refusal in ValidationFailed ────────────────────────────────

    @Test
    fun `B1 a home-unresolved rate refusal returns RATE_NOT_ELIGIBLE_HOME_UNRESOLVED`() =
        runTest(timeout = 60.seconds) {
            coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
                CreateExpenseResult.ValidationFailed(
                    errors = listOf("synthetic detail that must not surface"),
                    rateRefusal = RateRefusal(RateRefusalReason.HOME_CURRENCY_UNRESOLVED)
                ),
                PostCommitActionBatch.empty("test")
            )

            val result = add(20.0, "USD")

            assertTrue("Expected Error, got $result", result is Result.Error)
            assertEquals(
                RateRefusalCodes.NOT_ELIGIBLE_HOME_UNRESOLVED,
                (result as Result.Error).message
            )
            assertEquals("RATE_NOT_ELIGIBLE_HOME_UNRESOLVED", result.message)
        }

    @Test
    fun `B1 a typed rate refusal wins over amount-precision codes in the same failure`() =
        runTest(timeout = 60.seconds) {
            coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
                CreateExpenseResult.ValidationFailed(
                    errors = listOf(RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED),
                    rateRefusal = RateRefusal(RateRefusalReason.STALE_RATE)
                ),
                PostCommitActionBatch.empty("test")
            )

            val result = add(20.0, "USD")

            assertEquals(RateRefusalCodes.NOT_ELIGIBLE_STALE, (result as Result.Error).message)
        }

    @Test
    fun `B1 a flagged precision failure returns AMOUNT_PRECISION_EXCEEDED alone`() =
        runTest(timeout = 60.seconds) {
            coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
                CreateExpenseResult.ValidationFailed(
                    errors = listOf("other validation text", RateRefusalCodes.AMOUNT_PRECISION_EXCEEDED)
                ),
                PostCommitActionBatch.empty("test")
            )

            val result = add(12.5, "JPY")

            assertTrue("Expected Error, got $result", result is Result.Error)
            assertEquals("AMOUNT_PRECISION_EXCEEDED", (result as Result.Error).message)
        }

    @Test
    fun `B1 a flagged digits-unavailable failure returns AMOUNT_DIGITS_UNAVAILABLE alone`() =
        runTest(timeout = 60.seconds) {
            coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
                CreateExpenseResult.ValidationFailed(
                    errors = listOf("other validation text", RateRefusalCodes.AMOUNT_DIGITS_UNAVAILABLE)
                ),
                PostCommitActionBatch.empty("test")
            )

            val result = add(12.5, "USD")

            assertEquals("AMOUNT_DIGITS_UNAVAILABLE", (result as Result.Error).message)
        }

    @Test
    fun `B1 non-MEC validation failures keep the joined message`() = runTest(timeout = 60.seconds) {
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
            CreateExpenseResult.ValidationFailed(errors = listOf("first problem", "second problem")),
            PostCommitActionBatch.empty("test")
        )

        val result = add(20.0, "USD")

        assertTrue("Expected Error, got $result", result is Result.Error)
        assertEquals("first problem; second problem", (result as Result.Error).message)
    }

    // ── Loan-installments S11a: builder parity (INV-27 plain-path identity, MEC setter #1 stays pinned) ──

    private fun stubNormalizer(raw: String, normalized: String) {
        val canonical = mockk<MerchantCanonical>(relaxed = true)
        every { canonical.normalizedName } returns normalized
        val lookup = mockk<MerchantLookupResult>(relaxed = true)
        every { lookup.canonical } returns canonical
        coEvery { merchantNormalizer.normalize(raw, any(), any()) } returns lookup
    }

    private fun stubClassifier(categoryId: Long) {
        val classification = mockk<ClassificationResult>(relaxed = true)
        every { classification.categoryId } returns categoryId
        coEvery { hybridClassifier.classify(any(), any(), any(), any(), any(), any()) } returns classification
    }

    private suspend fun plainRequestFor(
        merchant: String,
        amount: Double,
        currency: String,
        categoryId: Long?,
        notes: String?
    ): CreateExpenseRequest {
        repository.addManualExpense(
            merchant = merchant,
            amount = amount,
            currency = currency,
            categoryId = categoryId,
            transactionType = TransactionType.PURCHASE,
            paymentMethod = PaymentMethod.CASH,
            date = now,
            notes = notes,
            entryZone = athens
        )
        return requestSlot.captured
    }

    private suspend fun installmentRequestFor(
        merchant: String,
        amount: Double,
        currency: String,
        categoryId: Long?,
        notes: String?
    ): CreateExpenseRequest = repository.buildInstallmentPurchaseRequest(
        merchant = merchant,
        amount = amount,
        currency = currency,
        categoryId = categoryId,
        transactionType = TransactionType.PURCHASE,
        paymentMethod = PaymentMethod.CASH,
        date = now,
        notes = notes
    )

    @Test
    fun `S11a parity the installment request equals the plain request except for the user-present flag`() =
        runTest(timeout = 60.seconds) {
            stubNormalizer("  cafe  ", "Cafe Normalised")
            stubClassifier(9L)

            val plain = plainRequestFor("  cafe  ", 600.0, "EUR", categoryId = null, notes = "synthetic note")
            val installment = installmentRequestFor("  cafe  ", 600.0, "EUR", categoryId = null, notes = "synthetic note")

            assertEquals("Cafe Normalised", installment.merchant)
            assertEquals(9L, installment.categoryId)
            assertEquals(plain.copy(userPresentRate = null), installment)
        }

    @Test
    fun `S11a parity the installment request carries no user-present rate while the plain save still sets it`() =
        runTest(timeout = 60.seconds) {
            stubNormalizer("Cafe", "Cafe")
            stubClassifier(9L)

            val plain = plainRequestFor("Cafe", 20.0, "USD", categoryId = 4L, notes = null)
            val installment = installmentRequestFor("Cafe", 20.0, "USD", categoryId = 4L, notes = null)

            assertEquals(UserPresentRateRequirement(athens), plain.userPresentRate)
            assertNull(installment.userPresentRate)
        }

    @Test
    fun `S11a parity an explicit category skips the classifier in both paths`() = runTest(timeout = 60.seconds) {
        stubNormalizer("Cafe", "Cafe")
        stubClassifier(9L)

        val plain = plainRequestFor("Cafe", 20.0, "EUR", categoryId = 4L, notes = null)
        val installment = installmentRequestFor("Cafe", 20.0, "EUR", categoryId = 4L, notes = null)

        assertEquals(4L, plain.categoryId)
        assertEquals(4L, installment.categoryId)
        coVerify(exactly = 0) { hybridClassifier.classify(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `S11a parity a classifier answer of zero or less leaves the category null in both paths`() =
        runTest(timeout = 60.seconds) {
            stubNormalizer("Cafe", "Cafe")
            stubClassifier(0L)

            val plain = plainRequestFor("Cafe", 20.0, "EUR", categoryId = null, notes = null)
            val installment = installmentRequestFor("Cafe", 20.0, "EUR", categoryId = null, notes = null)

            assertNull(plain.categoryId)
            assertNull(installment.categoryId)
        }

    @Test
    fun `S11a the installment request is never shared, not-mine, located or a transfer`() =
        runTest(timeout = 60.seconds) {
            stubNormalizer("Cafe", "Cafe")
            stubClassifier(9L)

            val request = installmentRequestFor("Cafe", 20.0, "EUR", categoryId = null, notes = null)

            assertEquals(false, request.isNotMine)
            assertNull(request.ownerName)
            assertEquals(false, request.isSharedExpense)
            assertNull(request.sharedWithName)
            assertNull(request.mySharePercentage)
            assertNull(request.myShareAmount)
            assertNull(request.latitude)
            assertNull(request.longitude)
            assertNull(request.locationSource)
            assertNull(request.transferDirection)
            assertNull(request.transferAccountName)
            assertEquals(true, request.isManualEntry)
        }

    @Test
    fun `S11a building the installment request writes nothing through the coordinator or the recurring store`() =
        runTest(timeout = 60.seconds) {
            stubNormalizer("Cafe", "Cafe")
            stubClassifier(9L)

            installmentRequestFor("Cafe", 20.0, "USD", categoryId = null, notes = null)

            coVerify(exactly = 0) { coordinator.createExpenseDbOnlyV2(any()) }
            coVerify(exactly = 0) { recurringExpenseRepository.insert(any()) }
        }

    @Test
    fun `S11a plain addManualExpense keeps its guards after the builder extraction`() = runTest(timeout = 60.seconds) {
        // The cap, the recurrence rule and the flag are still applied before and around the shared builder.
        assertTrue(add(1_000_000.01, "EUR") is Result.Error)
        assertTrue(add(20.0, "USD", RecurrenceFrequency.MONTHLY) is Result.Error)
        coVerify(exactly = 0) { coordinator.createExpenseDbOnlyV2(any()) }

        assertTrue(add(20.0, "USD") is Result.Success)
        assertEquals(UserPresentRateRequirement(athens), requestSlot.captured.userPresentRate)
    }
}
