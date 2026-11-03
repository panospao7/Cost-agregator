package com.yourname.expensetracker.ui.screens.review

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.usecase.ExplainPendingReviewUseCase
import com.yourname.expensetracker.domain.ai.usecase.JudgePendingReviewDuplicateUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestCategoryFallbackUseCase
import com.yourname.expensetracker.domain.ai.usecase.SuggestReceiptExtractionUseCase
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.location.GeocodingService
import com.yourname.expensetracker.domain.model.Result
import com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.ui.screens.debug.DebugDataStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * J-05-1 regression at the view model: approve-all on row A with the values the edit dialog submits
 * (resolveTransferSubmit) must hand row B null direction/account when A was not edited, so B keeps
 * its own suggestion, and must hand B the edited direction when A's direction was edited.
 * The repository is a mock here, so its per-row fallback to the suggestion is not exercised.
 * Synthetic values only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelBulkTransferApproveTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var reviewQueueRepository: ReviewQueueRepository
    private lateinit var viewModel: ReviewViewModel

    private val merchant = "Synthetic Transfer Merchant"
    private val rowAId = 101L
    private val rowBId = 102L

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val notificationRepository = mockk<NotificationRepository>(relaxed = true)
        reviewQueueRepository = mockk(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
        val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        val debugDataStorage = mockk<DebugDataStorage>(relaxed = true)
        val geocodingService = mockk<GeocodingService>(relaxed = true)
        val aiArtifactRepository = mockk<AiArtifactRepository>(relaxed = true)
        val aiSettingsRepository = mockk<AiSettingsRepository>(relaxed = true)
        val aiRuntimeDiagnostics = spyk(AiRuntimeDiagnostics(mockk<TimeProvider> { every { now() } returns 1_000L }))

        every { reviewQueueRepository.getAllPendingReviews() } returns flowOf(emptyList())
        every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(0)
        every { categoryRepository.allCategories } returns flowOf(emptyList())
        coEvery { debugDataStorage.load() } returns null
        every { aiSettingsRepository.settings() } returns flowOf(AiSettings())

        val rowA = mockk<PendingReview>(relaxed = true) {
            every { id } returns rowAId
            every { suggestedMerchant } returns merchant
            every { suggestedDirection } returns "OUTGOING"
            every { suggestedAccountName } returns "Synthetic Savings"
        }
        val rowB = mockk<PendingReview>(relaxed = true) {
            every { id } returns rowBId
            every { suggestedMerchant } returns merchant
            every { suggestedDirection } returns "INCOMING"
            every { suggestedAccountName } returns "Synthetic Main"
        }
        coEvery { reviewQueueRepository.getReviewById(rowAId) } returns rowA
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant(merchant) } returns listOf(rowA, rowB)
        coEvery {
            reviewQueueRepository.approveReview(
                reviewId = any(), finalAmount = any(), finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = any(), finalTransferAccountName = any(),
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        } answers { Result.Success(firstArg<Long>()) }

        viewModel = ReviewViewModel(
            notificationRepository,
            reviewQueueRepository,
            categoryRepository,
            receiptRepository,
            expenseRepository,
            debugDataStorage,
            geocodingService,
            mockk(relaxed = true),
            mockk<ExplainPendingReviewUseCase>(relaxed = true),
            mockk<SuggestCategoryFallbackUseCase>(relaxed = true),
            mockk<SuggestReceiptExtractionUseCase>(relaxed = true),
            mockk<JudgePendingReviewDuplicateUseCase>(relaxed = true),
            aiArtifactRepository,
            aiSettingsRepository,
            aiRuntimeDiagnostics,
            receiptLifecycleCoordinator = mockk<ReceiptLifecycleCoordinator>(relaxed = true),
            receiptDebugExporter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Row A's dialog is seeded OUTGOING/"Synthetic Savings"; state is what the user left in it. */
    private fun submitFromRowA(direction: TransferDirection?, account: String): ReviewTransferSubmit =
        resolveTransferSubmit(
            selectedType = TransactionType.TRANSFER,
            transferDirection = direction,
            transferAccount = account,
            seededDirection = TransferDirection.OUTGOING,
            seededAccount = "Synthetic Savings"
        )

    private fun approveAllFromRowA(submit: ReviewTransferSubmit) {
        viewModel.approveReviewWithEdits(
            reviewId = rowAId,
            finalAmount = null,
            finalMerchant = null,
            finalCategoryId = null,
            finalDate = null,
            finalType = null,
            finalTransferDirection = submit.direction,
            finalTransferAccountName = submit.accountName,
            applyToAll = false,
            approveAllPending = true
        )
    }

    @Test
    fun `approve-all on an unedited row A hands row B null direction and account`() = runTest {
        approveAllFromRowA(submitFromRowA(TransferDirection.OUTGOING, "Synthetic Savings"))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            reviewQueueRepository.approveReview(
                reviewId = rowBId, finalAmount = null, finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = null, finalTransferAccountName = null,
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
        // Row A itself also submits null/null, so it too keeps its own suggestion.
        coVerify(exactly = 1) {
            reviewQueueRepository.approveReview(
                reviewId = rowAId, finalAmount = any(), finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = null, finalTransferAccountName = null,
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
    }

    @Test
    fun `approve-all never hands row B row A's seeded OUTGOING and Savings`() = runTest {
        approveAllFromRowA(submitFromRowA(TransferDirection.OUTGOING, "Synthetic Savings"))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            reviewQueueRepository.approveReview(
                reviewId = rowBId, finalAmount = any(), finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = TransferDirection.OUTGOING, finalTransferAccountName = any(),
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
        coVerify(exactly = 0) {
            reviewQueueRepository.approveReview(
                reviewId = rowBId, finalAmount = any(), finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = any(), finalTransferAccountName = "Synthetic Savings",
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
    }

    @Test
    fun `direction edit on row A reaches row B and the account stays null`() = runTest {
        approveAllFromRowA(submitFromRowA(TransferDirection.INCOMING, "Synthetic Savings"))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            reviewQueueRepository.approveReview(
                reviewId = rowBId, finalAmount = null, finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = TransferDirection.INCOMING, finalTransferAccountName = null,
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
    }

    @Test
    fun `account edit on row A reaches row B and the direction stays null`() = runTest {
        approveAllFromRowA(submitFromRowA(TransferDirection.OUTGOING, "Synthetic Edited Account"))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            reviewQueueRepository.approveReview(
                reviewId = rowBId, finalAmount = null, finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = null, finalTransferAccountName = "Synthetic Edited Account",
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
    }
}
