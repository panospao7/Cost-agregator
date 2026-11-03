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

    /** Re-stubs rows A and B with the given suggestion seeds (null = the row has no suggestion). */
    private fun stubRows(
        aDirection: String?, aAccount: String?,
        bDirection: String?, bAccount: String?
    ) {
        val rowA = mockk<PendingReview>(relaxed = true) {
            every { id } returns rowAId
            every { suggestedMerchant } returns merchant
            every { suggestedDirection } returns aDirection
            every { suggestedAccountName } returns aAccount
        }
        val rowB = mockk<PendingReview>(relaxed = true) {
            every { id } returns rowBId
            every { suggestedMerchant } returns merchant
            every { suggestedDirection } returns bDirection
            every { suggestedAccountName } returns bAccount
        }
        coEvery { reviewQueueRepository.getReviewById(rowAId) } returns rowA
        coEvery { reviewQueueRepository.getPendingReviewsByMerchant(merchant) } returns listOf(rowA, rowB)
    }

    private fun approveAllRaw(direction: TransferDirection?, account: String?) {
        viewModel.approveReviewWithEdits(
            reviewId = rowAId,
            finalAmount = null,
            finalMerchant = null,
            finalCategoryId = null,
            finalDate = null,
            finalType = null,
            finalTransferDirection = direction,
            finalTransferAccountName = account,
            applyToAll = false,
            approveAllPending = true
        )
    }

    private fun verifyApproved(rowId: Long, direction: TransferDirection?, account: String?) {
        coVerify(exactly = 1) {
            reviewQueueRepository.approveReview(
                reviewId = rowId, finalAmount = any(), finalCurrency = any(), finalMerchant = any(),
                finalCategoryId = any(), finalDate = any(), finalType = any(),
                finalTransferDirection = direction, finalTransferAccountName = account,
                locationCleared = any(), finalLatitude = any(), finalLongitude = any(),
                finalAddress = any(), finalPlaceId = any()
            )
        }
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
        // m2: B shares A's seeds (OUTGOING / Savings), so the edit reaches it.
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", "Synthetic Savings")
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
        // m2: B shares A's seeds (OUTGOING / Savings), so the edit reaches it.
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", "Synthetic Savings")
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

    @Test
    fun `m2 account edit on row A does not reach row B whose seeded account differs`() = runTest {
        // Default rows: A OUTGOING/Savings, B INCOMING/Main.
        approveAllFromRowA(submitFromRowA(TransferDirection.OUTGOING, "Synthetic Joint"))
        advanceUntilIdle()

        verifyApproved(rowBId, direction = null, account = null)
        verifyApproved(rowAId, direction = null, account = "Synthetic Joint")
    }

    @Test
    fun `m2 direction edit on row A does not reach row B whose seeded direction differs`() = runTest {
        // Default rows: A OUTGOING/Savings, B INCOMING/Main.
        approveAllFromRowA(submitFromRowA(TransferDirection.INCOMING, "Synthetic Savings"))
        advanceUntilIdle()

        verifyApproved(rowBId, direction = null, account = null)
        verifyApproved(rowAId, direction = TransferDirection.INCOMING, account = null)
    }

    @Test
    fun `m2 lowercase seeded direction on row B still matches`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "outgoing", "Synthetic Savings")
        approveAllFromRowA(submitFromRowA(TransferDirection.INCOMING, "Synthetic Savings"))
        advanceUntilIdle()

        verifyApproved(rowBId, direction = TransferDirection.INCOMING, account = null)
    }

    @Test
    fun `m2 account comparison is case-sensitive so a differently cased account does not match`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", "synthetic savings")
        approveAllFromRowA(submitFromRowA(TransferDirection.OUTGOING, "Synthetic Joint"))
        advanceUntilIdle()

        verifyApproved(rowBId, direction = null, account = null)
    }

    @Test
    fun `m2 both rows without suggestion receive the entered direction and account`() = runTest {
        // Regression caught in review: both-blank seeds must match.
        stubRows(null, null, null, null)
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Entered Account")
        advanceUntilIdle()

        verifyApproved(rowBId, TransferDirection.INCOMING, "Synthetic Entered Account")
        verifyApproved(rowAId, TransferDirection.INCOMING, "Synthetic Entered Account")
    }

    @Test
    fun `m2 blank-string seeds on both rows match like null seeds`() = runTest {
        stubRows("  ", "", null, "   ")
        approveAllRaw(TransferDirection.OUTGOING, "Synthetic Entered Account")
        advanceUntilIdle()

        verifyApproved(rowBId, TransferDirection.OUTGOING, "Synthetic Entered Account")
    }

    @Test
    fun `m2 suggested account on row A and none on row B sends row B a null account`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", null)
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Joint")
        advanceUntilIdle()

        // Direction seeds match, account seeds do not: only the direction reaches B.
        verifyApproved(rowBId, TransferDirection.INCOMING, null)
    }

    @Test
    fun `m2 no suggestion on row A and a suggestion on row B sends row B nulls`() = runTest {
        stubRows(null, null, "OUTGOING", "Synthetic Savings")
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Joint")
        advanceUntilIdle()

        verifyApproved(rowBId, direction = null, account = null)
        verifyApproved(rowAId, TransferDirection.INCOMING, "Synthetic Joint")
    }

    @Test
    fun `m2 direction matches but account does not so only the direction reaches row B`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", "Synthetic Main")
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Joint")
        advanceUntilIdle()

        verifyApproved(rowBId, TransferDirection.INCOMING, null)
        verifyApproved(rowAId, TransferDirection.INCOMING, "Synthetic Joint")
    }

    @Test
    fun `m2 account matches but direction does not so only the account reaches row B`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "INCOMING", "Synthetic Savings")
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Joint")
        advanceUntilIdle()

        verifyApproved(rowBId, null, "Synthetic Joint")
    }

    @Test
    fun `m2 the edited row itself always receives its edit even when row B does not match`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "INCOMING", "Synthetic Main")
        approveAllRaw(TransferDirection.INCOMING, "Synthetic Joint")
        advanceUntilIdle()

        verifyApproved(rowAId, TransferDirection.INCOMING, "Synthetic Joint")
        verifyApproved(rowBId, null, null)
    }

    @Test
    fun `m2 unedited approve-all hands every row null direction and account whatever the seeds`() = runTest {
        stubRows("OUTGOING", "Synthetic Savings", "OUTGOING", "Synthetic Savings")
        approveAllRaw(direction = null, account = null)
        advanceUntilIdle()

        verifyApproved(rowAId, null, null)
        verifyApproved(rowBId, null, null)
    }
}
