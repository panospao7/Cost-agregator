package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.ExportDataRepository
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.privacy.CompositePrivacyGate
import com.yourname.expensetracker.domain.privacy.ExportPrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapabilityHandlingPolicy
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExportOptionsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var exportDataRepository: ExportDataRepository
    private lateinit var timeProvider: TimeProvider
    private lateinit var restoreMaintenanceMode: RestoreMaintenanceMode
    private lateinit var privacyGate: PrivacyGate
    private lateinit var readBarrier: DatabaseReadBarrier
    private lateinit var viewModel: ExportOptionsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        exportDataRepository = mockk(relaxed = true)
        timeProvider = mockk(relaxed = true)
        restoreMaintenanceMode = mockk(relaxed = true)
        privacyGate = mockk(relaxed = true)
        readBarrier = mockk<DatabaseReadBarrier>(relaxed = true)

        every { restoreMaintenanceMode.isWritesAllowed() } returns true
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        // ViewModel uses getExpensesBetween (not getExpensesBetweenForExport)
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        // ViewModel uses getExpensesPage for streaming
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()
        coEvery { exportDataRepository.createExportFile(any(), any()) } returns File(System.getProperty("java.io.tmpdir"), "test_export.csv")

        viewModel = buildViewModel(privacyGate)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(gate: PrivacyGate): ExportOptionsViewModel =
        ExportOptionsViewModel(
            exportDataRepository = exportDataRepository,
            accountingExportPolicy = AccountingExportPolicy(),
            timeProvider = timeProvider,
            xeroExporter = XeroCSVExporter(),
            quickBooksExporter = QuickBooksIIFExporter(),
            freshBooksExporter = FreshBooksExporter(),
            readBarrier = readBarrier,
            privacyGate = gate,
            exportJobSerializer = ExportJobSerializer(),
            ioDispatcher = testDispatcher
        )

    /**
     * Builds the REAL production composite gate ([CompositePrivacyGate] wrapping
     * [ExportPrivacyGate]) so tests observe actual gate routing — unlike the
     * relaxed `privacyGate` mock above, this catches P12-REG-01 (the ViewModel
     * requesting a capability the gate denies).
     */
    private fun realCompositeGate(
        encryptedBackupEnabled: Boolean = true,
        debugDataPersistenceEnabled: Boolean = false
    ): PrivacyGate {
        val settings = PrivacySettings(
            encryptedBackupEnabled = encryptedBackupEnabled,
            debugDataPersistenceEnabled = debugDataPersistenceEnabled
        )
        val repo = mockk<PrivacySettingsRepository>(relaxed = true)
        coEvery { repo.getSettings() } returns settings
        every { repo.observeSettings() } returns flowOf(settings)
        every { repo.observeLoadState() } returns flowOf(PrivacySettingsLoadState.Loaded(settings))
        coEvery { repo.getLoadState() } returns PrivacySettingsLoadState.Loaded(settings)
        val auditLogger = mockk<PrivacyAuditLogger>(relaxed = true)
        return CompositePrivacyGate(
            gates = listOf(ExportPrivacyGate(repo, isDebugBuild = false)),
            auditLogger = auditLogger,
            gateHandledCapabilities = PrivacyCapabilityHandlingPolicy.gateHandledCapabilities
        )
    }

    @Test
    fun `generate generic csv keeps only preview and file path`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val out = createTempFile(prefix = "export_viewmodel_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        // P12-P1-06: CSV header now includes BusinessCategory, BusinessProject, RequiresReceipt
        assertTrue((state.exportPreview ?: "").contains("ID,Date"))
        assertTrue((state.exportPreview ?: "").contains("BusinessCategory"))
        assertTrue((state.exportPreview ?: "").contains("BusinessProject"))
        assertTrue((state.exportPreview ?: "").contains("RequiresReceipt"))
    }

    @Test
    fun `generate generic csv neutralizes spreadsheet formula fields in preview`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "=SUM(A1:A2)"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val out = createTempFile(prefix = "export_formula_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val preview = viewModel.uiState.value.exportPreview.orEmpty()
        // The neutralized merchant should appear in the CSV output
        assertTrue(preview.contains("'=SUM(A1:A2)"))
        // The CSV data row should contain the currency field
        assertTrue(preview.contains("EUR"))
    }

    @Test
    fun `export formats include json option`() {
        val formats = viewModel.uiState.value.exportFormats.map { it.id }
        assertTrue(formats.contains("json"))
    }

    @Test
    fun `generate json export emits stable schema and escaped strings`() = runBlocking {
        val expenses = listOf(
            createExpense(
                merchant = "Cafe \"Central\"",
                notes = "line1\nline2\\"
            )
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns expenses
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        coEvery { exportDataRepository.getCategoryNameMap() } returns mapOf(1L to "Food")

        val out = createTempFile(prefix = "export_json_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.selectFormat("json")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        val preview = state.exportPreview.orEmpty()

        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertTrue(preview.contains("\"schemaVersion\":2"))
        assertTrue(preview.contains("\"exportType\":\"expenses\""))
        assertTrue(preview.contains("\"merchant\":\"Cafe \\\"Central\\\"\""))
        assertTrue(preview.contains("\"notes\":\"line1\\n2\\\\\""))
    }

    @Test
    fun `generate json export includes businessCategory businessProject requiresReceipt fields`() = runBlocking {
        val expenses = listOf(
            createExpense(
                merchant = "BizExpense",
                isBusinessExpense = true,
                businessPurpose = "Client meeting",
                businessCategory = "Travel",
                businessProject = "ProjectX",
                requiresReceipt = true
            )
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        coEvery { exportDataRepository.getCategoryNameMap() } returns mapOf(1L to "Food")

        val out = createTempFile(prefix = "export_json_biz_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.selectFormat("json")
        viewModel.generateExport()
        val preview = viewModel.uiState.value.exportPreview.orEmpty()

        // P12-P1-06: Verify business/tax fields are present in JSON output
        assertTrue("JSON must contain businessCategory", preview.contains("\"businessCategory\":\"Travel\""))
        assertTrue("JSON must contain businessProject", preview.contains("\"businessProject\":\"ProjectX\""))
        assertTrue("JSON must contain requiresReceipt", preview.contains("\"requiresReceipt\":true"))
    }

    @Test
    fun `generate generic csv includes businessCategory businessProject requiresReceipt columns`() = runBlocking {
        val expenses = listOf(
            createExpense(
                merchant = "BizExpense",
                isBusinessExpense = true,
                businessPurpose = "Client meeting",
                businessCategory = "Travel",
                businessProject = "ProjectX",
                requiresReceipt = true
            )
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        coEvery { exportDataRepository.getCategoryNameMap() } returns mapOf(1L to "Food")

        val out = createTempFile(prefix = "export_csv_biz_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val preview = viewModel.uiState.value.exportPreview.orEmpty()

        // P12-P1-06: Verify CSV header contains the new columns
        assertTrue("CSV header must contain BusinessCategory", preview.contains("BusinessCategory"))
        assertTrue("CSV header must contain BusinessProject", preview.contains("BusinessProject"))
        assertTrue("CSV header must contain RequiresReceipt", preview.contains("RequiresReceipt"))
        // Verify data row contains the values
        assertTrue("CSV data must contain Travel", preview.contains("Travel"))
        assertTrue("CSV data must contain ProjectX", preview.contains("ProjectX"))
        assertTrue("CSV data must contain true for requiresReceipt", preview.contains(",true,"))
    }

    @Test
    fun `generate xero export surfaces mixed currency policy failure`() = runBlocking {
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 2
        coEvery {
            exportDataRepository.getExpensesBetween(any(), any())
        } returns listOf(
            createExpense(id = 1L, merchant = "Cafe", currency = "EUR"),
            createExpense(id = 2L, merchant = "Hotel", currency = "USD")
        )
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } returns emptyList()

        viewModel.selectFormat("xero")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("single-currency dataset"))
    }

    @Test
    fun `generate quickbooks export surfaces non purchase policy failure`() = runBlocking {
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 1
        coEvery {
            exportDataRepository.getExpensesBetween(any(), any())
        } returns listOf(
            createExpense(id = 1L, merchant = "ATM", transactionType = TransactionType.WITHDRAWAL)
        )
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } returns emptyList()

        viewModel.selectFormat("quickbooks")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("PURCHASE transactions only"))
    }

    @Test
    fun `generate freshbooks export rejects empty dataset`() = runBlocking {
        // RP-19 (19-C): accounting formats reject empty datasets — a
        // header-only accounting file is not importable by the target tool.
        val out = createTempFile(prefix = "export_empty_freshbooks_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.selectFormat("freshbooks")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("non-empty dataset"))
        // No final file and no header-only accounting output may be left behind.
        assertFalse(out.exists())
    }

    @Test
    fun `generate generic csv export succeeds with header-only file for empty dataset`() = runBlocking {
        // RP-19 (19-C): CSV/JSON may emit header-only files (with a warning).
        val out = createTempFile(prefix = "export_empty_csv_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
        assertTrue(out.readText().contains("ID,Date"))
        assertTrue(out.readLines().size == 2) // metadata line + header line only
    }

    @Test
    fun `generate json export succeeds with header-only file for empty dataset`(): Unit = runBlocking {
        val out = createTempFile(prefix = "export_empty_json_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 0
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns emptyList()
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns emptyList()

        viewModel.selectFormat("json")
        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        val text = out.readText()
        assertTrue(text.contains("\"schemaVersion\":2"))
        assertTrue(text.contains("\"rows\":[]"))
        // Structural sanity: org.json can re-parse the header-only document.
        org.json.JSONObject(text)
    }

    @Test
    fun `cancelExport mid-stream removes final and temp files`() = runBlocking {
        // RP-19 (19-C): cancellation can never leave a successful final file.
        val dir = createTempDir(prefix = "export_cancel_")
        val out = File(dir, "expenses_9.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 3
        coEvery { exportDataRepository.getCategoryNameMap() } returns emptyMap()

        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } coAnswers {
            gate.await()
            emptyList<Expense>()
        }

        viewModel.generateExport()
        // Export is suspended inside the (mocked) page read.
        viewModel.cancelExport()
        gate.complete(Unit)
        // Unconfined dispatcher: cancellation cleanup runs inline on release.

        val state = viewModel.uiState.value
        assertTrue(state.error.orEmpty().contains("cancelled"))
        assertFalse(state.exportSuccess)
        assertFalse("no final export after cancellation", out.exists())
        val leftoverTemps = dir.listFiles { f -> f.name.startsWith(".tmp_") }.orEmpty()
        assertTrue("no temp leftovers after cancellation", leftoverTemps.isEmpty())
    }

    @Test
    fun `header rowCount may disagree with streamed rows under concurrent writes`() = runBlocking {
        // RP-19 (19-C): the non-snapshot keyset contract is retained (see the
        // roundtrip matrix). This pins the documented divergence: the header
        // rowCount comes from a separate count query and may exceed the number
        // of actually streamed rows when a concurrent insert lands mid-export.
        val streamed = listOf(
            createExpense(id = 1L, merchant = "A"),
            createExpense(id = 2L, merchant = "B"),
            createExpense(id = 3L, merchant = "C")
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 5
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns streamed
        var firstCall = true
        coEvery {
            exportDataRepository.getExpensesPage(any(), any(), any(), any(), any())
        } coAnswers {
            if (firstCall) {
                firstCall = false
                streamed
            } else {
                emptyList()
            }
        }
        val out = createTempFile(prefix = "export_concurrent_writes_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(state.exportSuccess)
        val text = out.readText()
        val dataRows = text.lines().count { line ->
            line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("ID,Date")
        }
        assertEquals("header claims rowCount=5 (count query, not snapshot)", 5, text.lines().first { it.startsWith("#") }.substringAfter("rowCount=").substringBefore(",").toInt())
        assertEquals("only 3 rows were actually streamed", 3, dataRows)
    }

    @Test
    fun `fx rate columns serialize without scientific notation or truncation`() = runBlocking {
        // RP-19 (19-C): rates use the dedicated formatter in both CSV and JSON.
        val expense = createExpense(merchant = "Cafe").copy(
            baseAmount = 11.23,
            exchangeRateUsed = 0.9123456
        )
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns 1
        coEvery { exportDataRepository.getExpensesBetween(any(), any()) } returns listOf(expense)
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns listOf(expense)
        val csvOut = createTempFile(prefix = "export_rate_csv_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns csvOut

        viewModel.generateExport()
        val csvText = csvOut.readText()
        assertTrue(csvText.contains("0.912346"))
        assertFalse("money-math serialization of rates must not be 2-decimal", csvText.contains(",0.91,"))
        assertFalse(csvText.contains("E-7"))

        val tinyRate = expense.copy(exchangeRateUsed = 0.0000001)
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns listOf(tinyRate)
        val jsonOut = createTempFile(prefix = "export_rate_json_", suffix = ".json")
        every { exportDataRepository.createExportFile(any(), any()) } returns jsonOut

        viewModel.selectFormat("json")
        viewModel.generateExport()
        val jsonText = jsonOut.readText()
        assertTrue(jsonText.contains("\"exchangeRateUsed\":0"))
        assertFalse("no scientific notation in JSON rates", jsonText.contains("1.0E-7"))
    }

    @Test
    fun `generate export reports repository file creation failure`() = runBlocking {
        val failure = IllegalStateException("cache dir unavailable")
        every { exportDataRepository.createExportFile(any(), any()) } throws failure

        viewModel.generateExport()
        val state = viewModel.uiState.value
        assertTrue(!state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertEquals("Failed to generate export. Please try again.", state.error)
        assertTrue(!state.isLoading)
    }

    // ── P12-REG-01: real composite-gate routing (regression guards) ────────────

    @Test
    fun `export succeeds through real composite gate with EXPENSE_EXPORT capability`() = runBlocking {
        // The REAL ExportPrivacyGate ALLOWS EXPENSE_EXPORT and DENIES RAWBACKUP_EXPORT.
        // If the ViewModel still requested RAWBACKUP_EXPORT (the regression), this
        // export would be denied. Asserting success proves the capability switch.
        val vm = buildViewModel(realCompositeGate())

        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        val out = createTempFile(prefix = "export_real_gate_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        vm.generateExport()
        val state = vm.uiState.value
        assertTrue("Normal export must NOT be denied by the real composite gate", state.exportSuccess)
        assertEquals(out.absolutePath, state.exportFilePath)
    }

    @Test
    fun `export succeeds through real composite gate even when raw backup is denied`() = runBlocking {
        // encryptedBackupEnabled=false → ExportPrivacyGate denies RAWBACKUP_EXPORT.
        // The old code requested RAWBACKUP_EXPORT and died here. EXPENSE_EXPORT is
        // independent of encryptedBackupEnabled, so the export must still succeed.
        val vm = buildViewModel(realCompositeGate(encryptedBackupEnabled = false))

        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses
        val out = createTempFile(prefix = "export_raw_denied_", suffix = ".csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        vm.generateExport()
        assertTrue(vm.uiState.value.exportSuccess)
    }

    // ── P12-NEW-01: fail-closed encryption ─────────────────────────────────────

    @Test
    fun `encrypted export with blank passphrase fails closed and writes no file`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size

        viewModel.generateExport(encryptExport = true, passphrase = "  ")
        val state = viewModel.uiState.value
        assertFalse(state.exportSuccess)
        assertEquals(null, state.exportFilePath)
        assertTrue(state.error.orEmpty().contains("passphrase"))
        // Must short-circuit before touching the repository at all.
        io.mockk.verify(exactly = 0) { exportDataRepository.createExportFile(any(), any()) }
    }

    @Test
    fun `encrypted export uses non-default passphrase and never leaves plaintext at final path`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val dir = createTempDir(prefix = "export_enc_")
        val out = File(dir, "expenses_1.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        // Simulate a real encryptor: capture the passphrase + write the dest .enc file.
        // encryptExportFile is NOT suspend → every. Assert on captured values AFTER the
        // call (assertions inside answers throw AssertionError through the coroutine).
        val passSlot = io.mockk.slot<String>()
        every {
            exportDataRepository.encryptExportFile(any(), any(), capture(passSlot))
        } answers {
            secondArg<File>().writeBytes(byteArrayOf(1, 2, 3))
        }

        viewModel.generateExport(encryptExport = true, passphrase = "s3cret-passphrase")
        val state = viewModel.uiState.value
        assertTrue("export should succeed: ${state.error}", state.exportSuccess)
        // P12-NEW-01: passphrase is the user secret, never the old hardcoded "default".
        assertTrue("encryptor was invoked with a passphrase", passSlot.isCaptured)
        assertEquals("s3cret-passphrase", passSlot.captured)
        assertFalse("must not use hardcoded default", passSlot.captured == "default")
        // Final path is the encrypted file…
        assertTrue(state.exportFilePath!!.endsWith(".enc"))
        // …and the plaintext temp must be gone (fail-closed cleanup in `finally`).
        assertFalse(File(out.parentFile, ".tmp_${out.name}").exists())
        // The plaintext final path was never produced for the encrypted flow.
        assertFalse(out.exists())
    }

    @Test
    fun `encrypted export deletes plaintext when encryption fails`() = runBlocking {
        val expenses = listOf(createExpense(merchant = "Coffee"))
        coEvery { exportDataRepository.countExpensesBetween(any(), any()) } returns expenses.size
        coEvery { exportDataRepository.getExpensesPage(any(), any(), any(), any(), any()) } returns expenses

        val dir = createTempDir(prefix = "export_enc_fail_")
        val out = File(dir, "expenses_1.csv")
        every { exportDataRepository.createExportFile(any(), any()) } returns out

        every {
            exportDataRepository.encryptExportFile(any(), any(), any())
        } throws java.io.IOException("disk full")

        viewModel.generateExport(encryptExport = true, passphrase = "s3cret-passphrase")
        val state = viewModel.uiState.value
        assertFalse(state.exportSuccess)
        // No plaintext temp and no plaintext final file left behind.
        assertFalse(File(out.parentFile, ".tmp_${out.name}").exists())
        assertFalse(out.exists())
    }

    private fun createExpense(
        merchant: String,
        notes: String = "note",
        id: Long = 1L,
        currency: String = "EUR",
        transactionType: TransactionType = TransactionType.PURCHASE,
        isBusinessExpense: Boolean = false,
        businessPurpose: String? = null,
        businessCategory: String? = null,
        businessProject: String? = null,
        requiresReceipt: Boolean = false
    ): Expense {
        return Expense(
            id = id,
            amount = 12.34,
            currency = currency,
            merchant = merchant,
            transactionType = transactionType,
            date = 1_700_000_000_000L,
            categoryId = 1L,
            notes = notes,
            createdAt = System.currentTimeMillis(),
            isBusinessExpense = isBusinessExpense,
            businessPurpose = businessPurpose,
            businessCategory = businessCategory,
            businessProject = businessProject,
            requiresReceipt = requiresReceipt
        )
    }
}
