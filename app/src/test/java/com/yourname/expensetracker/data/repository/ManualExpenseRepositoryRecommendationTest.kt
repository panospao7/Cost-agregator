package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.GenerateTransactionInsightUseCase
import com.yourname.expensetracker.domain.ai.usecase.TransactionInsightOutcome
import com.yourname.expensetracker.domain.ai.usecase.TransactionInsightSkipReason
import com.yourname.expensetracker.domain.config.AppConfig
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.model.recommendation.DashboardFollowThroughRecommendation
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * B2d pass 2: ManualExpenseRepository consumes the on-device insight outcome only as an optional
 * artifact for recommendation text (INV-18, INV-4, INV-3; AE-28..31).
 */
class ManualExpenseRepositoryRecommendationTest {

    private val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val hybridClassifier = mockk<HybridExpenseClassifier>(relaxed = true)
    private val categorizationEngine = mockk<CategorizationEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
    private val insightUseCase = mockk<GenerateTransactionInsightUseCase>()
    private val engine = mockk<DashboardFollowThroughEngine>()
    private val recommendationRepository = mockk<RecommendationRepository>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val postCommitActionRunner = mockk<PostCommitActionRunner>(relaxed = true)
    private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val applicationScope = TestScope(testDispatcher)

    private val insertedExpense = mockk<Expense>(relaxed = true)
    private val recommendations = listOf(mockk<DashboardFollowThroughRecommendation>(relaxed = true))
    private val logged = mutableListOf<String>()
    private val loggedThrowables = mutableListOf<Throwable>()

    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logged += message
            if (t != null) loggedThrowables += t
        }
    }

    private lateinit var repository: ManualExpenseRepository

    @Before
    fun setup() {
        Timber.plant(tree)
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>()
            MerchantLookupResult(
                canonical = MerchantCanonical(id = 1L, normalizedName = name, searchKey = name.lowercase()),
                alias = null,
                confidence = 1.0f,
                matchType = MatchType.EXACT_MATCH
            )
        }
        coEvery { coordinator.createExpenseDbOnlyV2(any()) } returns MutationResult(
            CreateExpenseResult.Created(5L),
            PostCommitActionBatch.empty("test")
        )
        coEvery { expenseDao.getById(5L) } returns insertedExpense
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = true))
        coEvery { engine.generateRecommendations(any(), any(), any()) } returns recommendations
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode.EUR)

        repository = ManualExpenseRepository(
            writeBarrier = writeBarrier,
            context = context,
            database = database,
            expenseDao = expenseDao,
            merchantNormalizer = merchantNormalizer,
            hybridClassifier = hybridClassifier,
            categorizationEngine = categorizationEngine,
            timeProvider = timeProvider,
            aiSettingsRepository = aiSettingsRepository,
            generateTransactionInsightUseCase = insightUseCase,
            dashboardFollowThroughEngine = engine,
            recommendationRepository = recommendationRepository,
            transactionLifecycleCoordinator = coordinator,
            postCommitActionRunner = postCommitActionRunner,
            recurringExpenseRepository = recurringExpenseRepository,
            applicationScope = applicationScope,
            currencySettingsRepository = currencySettingsRepository
        )
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun add() = runBlocking {
        repository.addManualExpense(
            merchant = "Cafe",
            amount = 4.5,
            currency = "EUR",
            categoryId = 1L
        )
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `AE-28 Generated outcome passes its artifact to the recommendation engine`() {
        val artifact = mockk<AiArtifactRecord>(relaxed = true)
        coEvery { insightUseCase(insertedExpense) } returns TransactionInsightOutcome.Generated(artifact)

        add()

        coVerify(exactly = 1) { engine.generateRecommendations(insertedExpense, artifact, any()) }
        coVerify(exactly = 1) { recommendationRepository.saveAll(recommendations) }
    }

    @Test
    fun `AE-28 every Skipped reason still saves deterministic recommendations with a null artifact`() {
        val reasons = TransactionInsightSkipReason.entries
        for (reason in reasons) {
            coEvery { insightUseCase(insertedExpense) } returns TransactionInsightOutcome.Skipped(reason)
            add()
        }

        coVerify(exactly = reasons.size) { engine.generateRecommendations(insertedExpense, null, any()) }
        coVerify(exactly = reasons.size) { recommendationRepository.saveAll(recommendations) }
    }

    @Test
    fun `AE-28 aiEnabled false gate skips the use case and the recommendation engine`() {
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings(aiEnabled = false))

        add()

        coVerify(exactly = 0) { insightUseCase(any()) }
        coVerify(exactly = 0) { engine.generateRecommendations(any(), any(), any()) }
        coVerify(exactly = 0) { recommendationRepository.saveAll(any()) }
    }

    @Test
    fun `AE-30 recommendation job outer timeout drops the job with a bounded log and saves nothing`() {
        assertTrue(
            "the outer recommendation budget must exceed the insight budget",
            AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS > AppConfig.Ai.TRANSACTION_INSIGHT_TIMEOUT_MS
        )
        coEvery { insightUseCase(insertedExpense) } coAnswers { delay(Long.MAX_VALUE); error("unreachable") }

        add()

        coVerify(exactly = 0) { recommendationRepository.saveAll(any()) }
        assertTrue(
            "timeout is logged with the configured bound only: $logged",
            logged.any { it.contains("timed out after ${AppConfig.Ai.RECOMMENDATION_JOB_TIMEOUT_MS}ms") }
        )
    }

    @Test
    fun `AE-31 cancellation from the use case propagates and is not logged or saved`() {
        coEvery { insightUseCase(insertedExpense) } coAnswers { throw CancellationException("cancelled") }

        add()

        coVerify(exactly = 0) { recommendationRepository.saveAll(any()) }
        assertTrue("cancellation must not be logged as a failure: $logged", logged.isEmpty())
        assertTrue("cancellation must not be logged with a throwable", loggedThrowables.isEmpty())

        // The semaphore permit and the application scope survive: a later expense still works.
        coEvery { insightUseCase(insertedExpense) } returns
            TransactionInsightOutcome.Skipped(TransactionInsightSkipReason.TIMEOUT)
        add()
        coVerify(exactly = 1) { recommendationRepository.saveAll(recommendations) }
    }

    @Test
    fun `AE-29 an unexpected exception is logged by class name only and deterministic work is dropped`() {
        coEvery { insightUseCase(insertedExpense) } coAnswers { throw IllegalStateException("SECRET-MERCHANT-TEXT") }

        add()

        coVerify(exactly = 0) { recommendationRepository.saveAll(any()) }
        assertTrue("a failure must be logged: $logged", logged.isNotEmpty())
        assertTrue("the throwable must not be logged", loggedThrowables.isEmpty())
        assertFalse(
            "no exception message may reach the log: $logged",
            logged.any { it.contains("SECRET-MERCHANT-TEXT") }
        )
        assertEquals(
            "only the bounded class name is logged",
            1,
            logged.count { it.contains("IllegalStateException") }
        )
    }
}
