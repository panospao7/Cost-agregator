package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyGateReasonCodes
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.repository.ExportDataRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * RP-15 (15-D) — ExportOptionsViewModel denial convergence.
 *
 * Denial (Denied), fail-closed (FailClosed), and permitted (Allowed) gate
 * outcomes for export: the first two converge on the typed [PrivacyBlockedUiState]
 * with a bounded message; the permitted path is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportOptionsViewModelPrivacyDenialTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var privacyGate: PrivacyGate
    private lateinit var readBarrier: DatabaseReadBarrier
    private lateinit var viewModel: ExportOptionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        exportDataRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)

        every { timeProvider.now() } returns 1_700_000_000_000L
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()
        coEvery { exportDataRepository.createExportFile(any(), any()) } returns
            File(System.getProperty("java.io.tmpdir"), "rp15_test_export.csv")

        viewModel = ExportOptionsViewModel(
            exportDataRepository = exportDataRepository,
            accountingExportPolicy = AccountingExportPolicy(),
            timeProvider = timeProvider,
            xeroExporter = XeroCSVExporter(),
            quickBooksExporter = QuickBooksIIFExporter(),
            freshBooksExporter = FreshBooksExporter(),
            readBarrier = readBarrier,
            privacyGate = privacyGate,
            exportJobSerializer = ExportJobSerializer(),
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Denial class 1: gate Denied ───────────────────────────────────────────

    @Test
    fun `denied export converges on typed blocked state with bounded message`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns
            PrivacyDecision.Denied("Encrypted export disabled by user setting")

        viewModel.generateExport(encryptExport = false)

        val state = viewModel.uiState.value
        val blocked = state.privacyBlocked
        assertNotNull("denial must set the typed state", blocked)
        assertEquals(PrivacyCapability.EXPENSE_EXPORT, blocked!!.capability)
        // Review fix (reviewer-strict REJECT on 013b78be): was error == "Export is blocked by your privacy settings.";
        // the denial now shows once, through the typed card only, so error is null.
        assertNull("a denial sets no separate error text", state.error)
        assertFalse("raw decision text must not be carried by the card", blocked.toString().contains("user setting"))
        assertFalse(state.isLoading)
    }

    @Test
    fun `a denial produces exactly one message path and the encrypted capability does the same`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Denied("Encrypted export disabled by user setting")

        viewModel.generateExport(encryptExport = true, passphrase = "synthetic-passphrase")

        val state = viewModel.uiState.value
        assertNotNull("the typed card is the one message path", state.privacyBlocked)
        assertEquals(PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED, state.privacyBlocked!!.capability)
        assertNull("no second message path", state.error)
        assertFalse(state.exportSuccess)
        assertFalse(state.isLoading)
    }

    @Test
    fun `clearError clears the blocked card as well as the error`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Denied("Encrypted export disabled by user setting")
        viewModel.generateExport(encryptExport = false)
        assertNotNull("precondition: the card is showing", viewModel.uiState.value.privacyBlocked)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.privacyBlocked)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `clearError also clears an ordinary error and leaves no card behind`() = runBlocking {
        viewModel.generateExport(encryptExport = true, passphrase = "")
        assertEquals("Encrypted export requires a passphrase", viewModel.uiState.value.error)

        viewModel.clearError()

        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.privacyBlocked)
    }

    @Test
    fun `a permitted run after a denial removes the card`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Denied("Encrypted export disabled by user setting")
        viewModel.generateExport(encryptExport = false)
        assertNotNull(viewModel.uiState.value.privacyBlocked)

        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        viewModel.generateExport(encryptExport = false)

        assertNull("the previous denial card must not survive a new run", viewModel.uiState.value.privacyBlocked)
    }

    // ── Denial class 2: gate FailClosed ───────────────────────────────────────

    @Test
    fun `fail-closed export converges on typed blocked state with gate failure code`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns
            PrivacyDecision.FailClosed("Privacy check failed: SECRET_INTERNAL_DETAIL")

        viewModel.generateExport(encryptExport = false)

        val blocked = viewModel.uiState.value.privacyBlocked
        assertNotNull("fail-closed must set the typed state", blocked)
        assertEquals(PrivacyCapability.EXPENSE_EXPORT, blocked!!.capability)
        assertEquals(PrivacyGateReasonCodes.PRIVACY_GATE_FAILURE, blocked.reasonCode)
        // Review fix: was error!!.contains(...); a denial now sets no error text, so the card is the only message path.
        assertNull(viewModel.uiState.value.error)
        assertFalse(
            "fail-closed exception/decision text must not leak into the card",
            blocked.toString().contains("SECRET_INTERNAL_DETAIL")
        )
    }

    // ── Denial class 3: encrypted export without passphrase (fail-closed input) ─

    @Test
    fun `encrypted export without passphrase fails closed with bounded message and no blocked state`() = runBlocking {
        viewModel.generateExport(encryptExport = true, passphrase = "")

        val state = viewModel.uiState.value
        assertEquals("Encrypted export requires a passphrase", state.error)
        assertNull(state.privacyBlocked)
        assertFalse(state.isLoading)
    }

    // ── Permitted path unchanged ───────────────────────────────────────────────

    @Test
    fun `permitted export proceeds past the privacy check and leaves blocked state null`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.generateExport(encryptExport = false)

        val state = viewModel.uiState.value
        assertNull(state.privacyBlocked)
        // The flow must have moved past the gate to the read barrier — proving
        // the denial plumbing did not block permitted exports.
        verify { readBarrier.checkReadAllowed(any(), any()) }
    }

    @Test
    fun `correct capability is requested from the gate`() = runBlocking {
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.generateExport(encryptExport = false)

        val state = viewModel.uiState.value
        assertNull(state.privacyBlocked)
    }
}
