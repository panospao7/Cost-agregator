package com.yourname.expensetracker.util

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * RP-19 (19-A): CSV ingestion boundary tests — character-stream RFC-4180
 * parsing, BOM stripping, and Locale.US date parsing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class CsvImportRfc4180Test {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private lateinit var importer: CsvExpenseImporter
    private var originalLocale: Locale? = null

    @Before
    fun setup() {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        io.mockk.every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        io.mockk.every { maintenanceMode.isWritesAllowed() } returns true
        importer = CsvExpenseImporter(
            categoryDao,
            coordinator,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode)
        )
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)
    }

    @After
    fun tearDown() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private fun captureRequest(): CreateExpenseRequest {
        val slot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(slot)) } returns CreateExpenseResult.Created(1L)
        // Re-run capture on demand is not possible; callers must stub before import.
        return slot.captured
    }

    @Test
    fun `quoted field with embedded LF stays one record and preserves the newline`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,25.50,Starbucks,Coffee,\"Morning\nlatte run\""

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
        assertThat(requestSlot.captured.notes).isEqualTo("Morning\nlatte run")
    }

    @Test
    fun `quoted field with embedded CRLF is preserved verbatim`() = runTest {
        // Two records are imported; capture them in call order — a single
        // slot would expose only the last request.
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,notes\r\n" +
            "2024-01-15,25.50,Starbucks,Coffee,\"Morning\r\nlatte\"\r\n" +
            "2024-01-16,10.00,Kiosk,Snacks,plain"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(2)
        assertThat(result.errors).isEqualTo(0)
        coVerify(exactly = 2) { coordinator.createExpense(any()) }
        assertThat(requests).hasSize(2)
        assertThat(requests.map { it.merchant }).containsExactly("Starbucks", "Kiosk").inOrder()
        assertThat(requests[0].notes).isEqualTo("Morning\r\nlatte")
        assertThat(requests[1].notes).isEqualTo("plain")
    }

    @Test
    fun `embedded doubled quotes decode to literal quotes`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,25.50,Starbucks,Coffee,\"He said \"\"hello\"\"\""

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(requestSlot.captured.notes).isEqualTo("He said \"hello\"")
    }

    @Test
    fun `malformed unclosed quote yields exactly one failed row and no spurious rows`() = runTest {
        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,25.50,\"Unclosed merchant,Coffee,never terminates"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(0)
        assertThat(result.errors).isEqualTo(1)
        assertThat(result.perRowResults.single())
            .isInstanceOf(CsvExpenseImporter.RowResult.Failed::class.java)
        coVerifyNoInsert()
    }

    @Test
    fun `rows before a malformed quote still import`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,1.00,Good,Cat,ok\n" +
            "2024-01-16,2.00,\"Bad,Cat,broken"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(1)
        assertThat(requestSlot.captured.merchant).isEqualTo("Good")
    }

    @Test
    fun `UTF-8 BOM at content start is stripped from the header`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "\uFEFFdate,amount,merchant,category,notes\n2024-01-15,25.50,Starbucks,Coffee,x"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
        assertThat(requestSlot.captured.amount).isEqualTo(25.50)
    }

    @Test
    fun `BOM after leading blank lines and comments is stripped from the header`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "\n\n# ExpenseTracker Export v2, rowCount=1\n\uFEFFdate,amount,merchant,category,notes\n" +
            "2024-01-15,25.50,Starbucks,Coffee,x"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
    }

    @Test
    fun `comment line inside a quoted multi-line field is not treated as a comment`() = runTest {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,25.50,Starbucks,Coffee,\"line one\n# not a comment\""

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
        assertThat(requestSlot.captured.notes).isEqualTo("line one\n# not a comment")
    }

    @Test
    fun `Arabic default locale with Arabic-Indic digits still parses ASCII dates`() = runTest {
        // RP-19 (19-A): the formatter is pinned to Locale.US. Under an
        // Arabic-Indic default FORMAT locale, an unpinned numeric pattern can
        // fail to parse plain ASCII digits (DecimalStyle digit shaping).
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale("ar", "EG"))

        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val result = importer.importFromContent(
            "date,amount,merchant,category,notes\n2024-01-15,25.50,Starbucks,Coffee,x"
        ) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
        assertThat(requestSlot.captured.date).isGreaterThan(0L)
    }

    @Test
    fun `Persian default locale still parses ASCII dates`() = runTest {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale("fa", "IR"))

        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val result = importer.importFromContent(
            "date,amount,merchant,category,notes\n2024-01-15,25.50,Starbucks,Coffee,x"
        ) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
    }

    private fun coVerifyNoInsert() {
        io.mockk.coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }
}
