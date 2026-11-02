package com.yourname.expensetracker.ui.screens.warranty

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository.EditWarrantyResult
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditInput
import com.yourname.expensetracker.ui.util.blockedWriteException
import com.yourname.expensetracker.util.ViewModelTestUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.File

/**
 * warranty-edit S3: [WarrantyTrackerViewModel.editWarranty] (INV-6, 14, 15, 16; AE-6, 16, 17).
 * Validation outcomes stay in-dialog, a repository failure uses the in-dialog banner state and
 * never the snackbar `mutationError`, and cancellation is not converted to an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WarrantyTrackerViewModelEditTest : ViewModelTestUtils() {

    private val warrantyRepository = mockk<WarrantyTrackerRepository>(relaxed = true)
    private lateinit var viewModel: WarrantyTrackerViewModel

    private val input = WarrantyEditInput(
        productName = "Pixel 9",
        merchantName = "Contoso",
        purchaseDate = 1_700_000_000_000L,
        durationMonths = 24,
        warrantyType = WarrantyType.EXTENDED,
        supportPhone = "+30 6900000000"
    )

    private val genericFailure = UiText.StringResource(R.string.warranty_mutation_failed)
    private val blockedMessage = UiText.StringResource(R.string.ui_write_blocked_maintenance)

    @Before
    override fun setup() {
        super.setup()
        coEvery { warrantyRepository.getAllWarranties() } returns MutableStateFlow(emptyList())
        viewModel = WarrantyTrackerViewModel(warrantyRepository, timeProvider = mockk(relaxed = true))
        // Drain init (loadWarranties/loadStats) before arming the scenario.
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun kotlinx.coroutines.test.TestScope.collectOutcomes(): List<WarrantyEditResult> {
        val outcomes = mutableListOf<WarrantyEditResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testDispatcher.scheduler)) {
            viewModel.editOutcomes.toList(outcomes)
        }
        return outcomes
    }

    @Test
    fun `edited emits Saved, refreshes stats, closes without any error state`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(7L, input) } returns
            EditWarrantyResult.Edited(7L, WarrantyStatus.ACTIVE, leftReview = false)
        coEvery { warrantyRepository.getActiveWarrantyCount() } returns 9

        viewModel.editWarranty(7L, input)
        advanceUntilIdle()

        assertEquals(listOf<WarrantyEditOutcome>(WarrantyEditOutcome.Saved(leftReview = false)), outcomes.map { it.outcome })
        assertEquals(listOf(7L), outcomes.map { it.warrantyId })
        assertEquals(9,viewModel.state.value.activeCount)
        assertNull(viewModel.state.value.editFormError)
        assertNull(viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
        coVerify(exactly = 1) { warrantyRepository.editWarranty(7L, input) }
        coVerify(exactly = 0) { warrantyRepository.updateWarranty(any()) }
    }

    @Test
    fun `review row save and confirm emits Saved with leftReview true`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(8L, input) } returns
            EditWarrantyResult.Edited(8L, WarrantyStatus.EXPIRED, leftReview = true)

        viewModel.editWarranty(8L, input)
        advanceUntilIdle()

        assertEquals(listOf<WarrantyEditOutcome>(WarrantyEditOutcome.Saved(leftReview = true)), outcomes.map { it.outcome })
        assertEquals(listOf(8L), outcomes.map { it.warrantyId })
        assertNull(viewModel.state.value.mutationError)
    }

    @Test
    fun `no change emits NoChange and refreshes stats`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(9L, input) } returns EditWarrantyResult.NoChange
        coEvery { warrantyRepository.getActiveWarrantyCount() } returns 4

        viewModel.editWarranty(9L, input)
        advanceUntilIdle()

        assertEquals(listOf<WarrantyEditOutcome>(WarrantyEditOutcome.NoChange), outcomes.map { it.outcome })
        assertEquals(listOf(9L), outcomes.map { it.warrantyId })
        assertEquals(4, viewModel.state.value.activeCount)
        assertNull(viewModel.state.value.editFormError)
    }

    @Test
    fun `invalid input emits Invalid reason and sets no snackbar or banner state`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(10L, input) } returns
            EditWarrantyResult.Invalid(WarrantyEditError.PRODUCT_BLANK)

        viewModel.editWarranty(10L, input)
        advanceUntilIdle()

        assertEquals(
            listOf<WarrantyEditOutcome>(WarrantyEditOutcome.Invalid(WarrantyEditError.PRODUCT_BLANK)),
            outcomes.map { it.outcome }
        )
        assertEquals(listOf(10L), outcomes.map { it.warrantyId })
        assertNull(viewModel.state.value.mutationError)
        assertNull(viewModel.state.value.editFormError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `NotFound and NotEditable emit one-shot outcomes without error state`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(11L, input) } returns EditWarrantyResult.NotFound
        coEvery { warrantyRepository.editWarranty(12L, input) } returns EditWarrantyResult.NotEditable

        viewModel.editWarranty(11L, input)
        advanceUntilIdle()
        viewModel.editWarranty(12L, input)
        advanceUntilIdle()

        assertEquals(
            listOf(WarrantyEditOutcome.NotFound, WarrantyEditOutcome.NotEditable),
            outcomes.map { it.outcome }
        )
        assertEquals(listOf(11L, 12L), outcomes.map { it.warrantyId })
        assertNull(viewModel.state.value.mutationError)
        assertNull(viewModel.state.value.editFormError)
    }

    @Test
    fun `repository failure sets the in-dialog error, not the snackbar error, and emits no outcome`() =
        runTest(testDispatcher) {
            val outcomes = collectOutcomes()
            coEvery { warrantyRepository.editWarranty(13L, input) } throws
                IllegalStateException("secret raw detail")

            viewModel.editWarranty(13L, input)
            advanceUntilIdle()

            assertEquals(genericFailure, viewModel.state.value.editFormError)
            assertNull(viewModel.state.value.mutationError)
            assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
            assertTrue(outcomes.isEmpty())
        }

    @Test
    fun `write barrier block sets the maintenance message in the dialog state`() = runTest(testDispatcher) {
        coEvery { warrantyRepository.editWarranty(14L, input) } throws blockedWriteException()

        viewModel.editWarranty(14L, input)
        advanceUntilIdle()

        assertEquals(blockedMessage, viewModel.state.value.editFormError)
        assertNull(viewModel.state.value.mutationError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
    }

    @Test
    fun `retry after a failure clears the in-dialog error and clearEditFormError resets it`() =
        runTest(testDispatcher) {
            val outcomes = collectOutcomes()
            coEvery { warrantyRepository.editWarranty(15L, input) } throws RuntimeException("x")
            viewModel.editWarranty(15L, input)
            advanceUntilIdle()
            assertEquals(genericFailure, viewModel.state.value.editFormError)

            coEvery { warrantyRepository.editWarranty(15L, input) } returns
                EditWarrantyResult.Edited(15L, WarrantyStatus.ACTIVE, leftReview = false)
            viewModel.editWarranty(15L, input)
            advanceUntilIdle()
            assertNull(viewModel.state.value.editFormError)
            assertEquals(listOf<WarrantyEditOutcome>(WarrantyEditOutcome.Saved(false)), outcomes.map { it.outcome })
            assertEquals(listOf(15L), outcomes.map { it.warrantyId })

            coEvery { warrantyRepository.editWarranty(16L, input) } throws RuntimeException("x")
            viewModel.editWarranty(16L, input)
            advanceUntilIdle()
            assertEquals(genericFailure, viewModel.state.value.editFormError)
            viewModel.clearEditFormError()
            assertNull(viewModel.state.value.editFormError)
        }

    /** AE-6: cancellation propagates; no message, id released. */
    @Test
    fun `cancellation propagates and is not converted to an error`() = runTest(testDispatcher) {
        val outcomes = collectOutcomes()
        coEvery { warrantyRepository.editWarranty(17L, input) } throws CancellationException("cancelled")

        viewModel.editWarranty(17L, input)
        advanceUntilIdle()

        assertNull(viewModel.state.value.mutationError)
        assertNull(viewModel.state.value.editFormError)
        assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
        assertTrue(outcomes.isEmpty())
    }

    /** AE-16: a double tap makes one repository call; the id is held while in flight. */
    @Test
    fun `double tap makes one repository call and refreshes stats once the call completes`() =
        runTest(testDispatcher) {
            val outcomes = collectOutcomes()
            val gate = CompletableDeferred<EditWarrantyResult>()
            coEvery { warrantyRepository.editWarranty(18L, input) } coAnswers { gate.await() }
            coEvery { warrantyRepository.getActiveWarrantyCount() } returns 3

            viewModel.editWarranty(18L, input)
            viewModel.editWarranty(18L, input)
            advanceUntilIdle()
            assertTrue(18L in viewModel.state.value.mutatingWarrantyIds)
            coVerify(exactly = 1) { warrantyRepository.editWarranty(18L, input) }

            gate.complete(EditWarrantyResult.Edited(18L, WarrantyStatus.ACTIVE, leftReview = false))
            advanceUntilIdle()

            coVerify(exactly = 1) { warrantyRepository.editWarranty(18L, input) }
            assertEquals(3, viewModel.state.value.activeCount)
            assertTrue(viewModel.state.value.mutatingWarrantyIds.isEmpty())
            assertEquals(1, outcomes.size)
            assertEquals(18L, outcomes.single().warrantyId)
        }

    /** M1: each result carries the id of the warranty it was produced for, never a neighbour's. */
    @Test
    fun `edits of two different warranties each emit a result tagged with their own id`() =
        runTest(testDispatcher) {
            val outcomes = collectOutcomes()
            coEvery { warrantyRepository.editWarranty(21L, input) } returns
                EditWarrantyResult.Edited(21L, WarrantyStatus.ACTIVE, leftReview = false)
            coEvery { warrantyRepository.editWarranty(22L, input) } returns
                EditWarrantyResult.Invalid(WarrantyEditError.PRODUCT_BLANK)

            viewModel.editWarranty(21L, input)
            advanceUntilIdle()
            viewModel.editWarranty(22L, input)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    WarrantyEditResult(21L, WarrantyEditOutcome.Saved(leftReview = false)),
                    WarrantyEditResult(22L, WarrantyEditOutcome.Invalid(WarrantyEditError.PRODUCT_BLANK))
                ),
                outcomes
            )
        }

    /** INV-14: logs carry bounded codes only, never user text or exception messages. */
    @Test
    fun `failure logs contain no user text and no exception message`() = runTest(testDispatcher) {
        val lines = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                lines += message
            }
        }
        Timber.plant(tree)
        try {
            coEvery { warrantyRepository.editWarranty(19L, input) } throws
                IllegalStateException("secret raw detail Pixel 9 Contoso")
            viewModel.editWarranty(19L, input)
            advanceUntilIdle()
            coEvery { warrantyRepository.editWarranty(20L, input) } returns
                EditWarrantyResult.Invalid(WarrantyEditError.PHONE_TOO_LONG)
            viewModel.editWarranty(20L, input)
            advanceUntilIdle()
        } finally {
            Timber.uproot(tree)
        }

        val joined = lines.joinToString("\n")
        assertTrue(joined.contains("IllegalStateException"))
        assertTrue(joined.contains("PHONE_TOO_LONG"))
        listOf("secret raw detail", "Pixel 9", "Contoso", "+30 6900000000").forEach {
            assertFalse("log leaked: $it", joined.contains(it))
        }
    }

    /** AE-17 / INV-16: the ViewModel never writes a warranty or builds a status/review copy. */
    @Test
    fun `view model source has no updateWarranty call and no status or needsReview copy`() {
        val source = listOf(
            "src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt",
            "app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt"
        ).map(::File).firstOrNull { it.isFile }?.readText()
        assertTrue("WarrantyTrackerViewModel.kt not found", source != null)

        val text = source!!
            .replace(Regex("""/\*[\s\S]*?\*/"""), "")
            .replace(Regex("""//[^\n]*"""), "")
        assertFalse(text.contains("updateWarranty("))
        assertFalse(text.contains("warrantyDao"))

        val reviewField = Regex("""\b(status|needsReview)\s*=(?!=)""")
        for (match in Regex("""\bcopy\s*\(""").findAll(text)) {
            var depth = 1
            var i = match.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            assertEquals("unbalanced copy( call", 0, depth)
            val args = text.substring(match.range.last + 1, i - 1)
            assertFalse("copy( assigns status/needsReview", reviewField.containsMatchIn(args))
        }
    }
}
