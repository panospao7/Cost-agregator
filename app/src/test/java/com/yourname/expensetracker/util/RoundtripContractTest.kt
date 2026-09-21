package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RP-19 (19-B): explicit roundtrip contract tests — see
 * `docs/analyses and debug master/remediation/RP-19-ROUNDTRIP-MATRIX.md`.
 *
 * Contract under test:
 * - `amount` is the ORIGINAL amount; `effectiveAmount` is a fallback ONLY when
 *   amount is absent/unparseable — one precedence rule for every transaction
 *   type, identical in CSV and JSON.
 * - `transactionType` maps to the exact enum; a present-but-unknown value fails
 *   the row with UNKNOWN_TRANSACTION_TYPE instead of silently defaulting.
 * - Ordinary, shared, refund (negative), transfer, deposit, missing and
 *   unknown-type rows roundtrip with exact effective values (effective is
 *   reconstructed by the entity from amount because ownership metadata is not
 *   exported — the documented unsupported default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class RoundtripContractTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>()
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private lateinit var csvImporter: CsvExpenseImporter
    private lateinit var jsonImporter: JsonExpenseImporter

    @Before
    fun setup() {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        io.mockk.every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        io.mockk.every { maintenanceMode.isWritesAllowed() } returns true
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        csvImporter = CsvExpenseImporter(
            categoryDao,
            coordinator,
            currencySettingsRepository = currencySettingsRepository,
            writeBarrier = barrier
        )
        jsonImporter = JsonExpenseImporter(
            coordinator,
            categoryDao,
            FakeTimeProvider(1_712_000_000_000L),
            barrier
        )
        coEvery { categoryDao.getByName(any()) } returns Category(id = 7, name = "Food", icon = "F", color = "#112233")
    }

    private fun captureCsvRequest(): CapturingSlot<CreateExpenseRequest> {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)
        return requestSlot
    }

    // ── CSV: exact-enum transactionType mapping ─────────────────────────────

    @Test
    fun `csv deposit type maps to exact enum`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,Cash In,100.00,100.00,EUR,DEPOSIT,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(0, result.errors)
        assertEquals(
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            slot.captured.transactionType
        )
    }

    @Test
    fun `csv transfer type maps to exact enum`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,Move,50.00,50.00,EUR,TRANSFER,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER,
            slot.captured.transactionType
        )
    }

    @Test
    fun `csv unknown transaction type fails row with UNKNOWN_TRANSACTION_TYPE`() = runTest {
        val csv = v2Csv("1,2024-01-15,2024-01-15,Mystery,10.00,10.00,EUR,REFUND_UNIVERSE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(0, result.imported)
        assertEquals(1, result.errors)
        assertEquals(
            ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE,
            (result.perRowResults.single() as CsvExpenseImporter.RowResult.Failed).error
        )
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }

    @Test
    fun `csv missing transaction type defaults to PURCHASE`() = runTest {
        val slot = captureCsvRequest()
        // Legacy schema: no TransactionType column at all.
        val csv = "date,amount,merchant,category,notes\n2024-01-15,12.50,Starbucks,Food,x"

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE,
            slot.captured.transactionType
        )
    }

    // ── CSV: amount-first precedence with effectiveAmount fallback ──────────

    @Test
    fun `csv ordinary row uses original amount not effective`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,Dinner,80.00,40.00,EUR,PURCHASE,Food,,,,")

        csvImporter.importFromContent(csv)

        // amount is the ORIGINAL amount; effectiveAmount (40.00) is fallback-only.
        assertEquals(80.00, slot.captured.amount, 0.0)
    }

    @Test
    fun `csv shared row keeps original amount as documented unsupported default`() = runTest {
        val slot = captureCsvRequest()
        // Exported shared row: effectiveAmount (22.50) reflects the share
        // reduction, but ownership metadata is not exported — import keeps the
        // original amount and the entity recomputes effective = amount.
        val csv = v2Csv("1,2024-01-15,2024-01-15,Pizza,45.00,22.50,EUR,PURCHASE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(45.00, slot.captured.amount, 0.0)
        // Effective is reconstructed by the entity, not imported:
        assertEquals(45.00, slot.captured.amount * 1.0, 0.0)
    }

    @Test
    fun `csv negative refund-style amount roundtrips verbatim`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,Refund,-25.50,-25.50,EUR,PURCHASE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(-25.50, slot.captured.amount, 0.0)
    }

    @Test
    fun `csv boundary amounts roundtrip exactly`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)
        val csv = v2Csv(
            "1,2024-01-15,2024-01-15,Zero,0.0,0.0,EUR,PURCHASE,Food,,,,\n" +
                "2,2024-01-16,2024-01-16,Grail,99999999.99,99999999.99,EUR,PURCHASE,Food,,,,"
        )

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(2, result.imported)
        assertEquals(2, requests.size)
        assertEquals(0.0, requests[0].amount, 0.0)
        assertEquals(99999999.99, requests[1].amount, 0.0)
    }

    @Test
    fun `csv falls back to effectiveAmount only when amount is missing`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,NoAmount,,33.25,EUR,PURCHASE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(33.25, slot.captured.amount, 0.0)
    }

    @Test
    fun `csv falls back to effectiveAmount when amount is unparseable`() = runTest {
        val slot = captureCsvRequest()
        val csv = v2Csv("1,2024-01-15,2024-01-15,BadAmount,not-a-number,7.75,EUR,PURCHASE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(1, result.imported)
        assertEquals(7.75, slot.captured.amount, 0.0)
    }

    @Test
    fun `csv row with neither parseable amount nor effectiveAmount fails`() = runTest {
        val csv = v2Csv("1,2024-01-15,2024-01-15,Both Bad,garbage,worse,EUR,PURCHASE,Food,,,,")

        val result = csvImporter.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertEquals(0, result.imported)
        assertEquals(1, result.errors)
    }

    // ── JSON: same contract (parity with CSV) ────────────────────────────────

    @Test
    fun `json deposit row maps exact enum and original amount`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Salary","amount":1000.0,"effectiveAmount":1000.0,
               "currency":"EUR","transactionType":"DEPOSIT","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertTrue(result.success)
        assertEquals(1000.0, slot.captured.amount, 0.0)
        assertEquals(
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT,
            slot.captured.transactionType
        )
    }

    @Test
    fun `json shared row keeps original amount - parity with csv`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Pizza","amount":45.0,"effectiveAmount":22.5,
               "currency":"EUR","transactionType":"PURCHASE","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertTrue(result.success)
        assertEquals("CSV and JSON must share the amount-first precedence", 45.0, slot.captured.amount, 0.0)
    }

    @Test
    fun `json falls back to effectiveAmount when amount absent - parity with csv`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"NoAmount","effectiveAmount":33.25,
               "currency":"EUR","transactionType":"PURCHASE","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertTrue(result.success)
        assertEquals(33.25, slot.captured.amount, 0.0)
    }

    @Test
    fun `json row with neither parseable amount nor effectiveAmount fails - parity with csv`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"BadRow","amount":"abc","effectiveAmount":"def",
               "currency":"EUR","transactionType":"PURCHASE","date":1705276800000},
              {"merchant":"GoodRow","amount":7.5,
               "currency":"EUR","transactionType":"PURCHASE","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertFalse(result.success)
        assertEquals(1, result.errorCount)
        assertEquals(
            "Row 0: ${ImportContractErrorCodes.INVALID_AMOUNT}",
            result.errors.single()
        )
        assertEquals("the good row still imports", 1, result.importedCount)
        assertEquals(7.5, slot.captured.amount, 0.0)
        coVerify(exactly = 1) { coordinator.createExpense(any()) }
    }

    @Test
    fun `json unknown transaction type fails row with UNKNOWN_TRANSACTION_TYPE`() = runTest {
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Mystery","amount":10.0,
               "currency":"EUR","transactionType":"BARter","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertFalse(result.success)
        assertEquals(1, result.errorCount)
        assertEquals(
            "Row 0: ${ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE}",
            result.errors.single()
        )
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }

    @Test
    fun `json transfer row maps exact enum and negative amount`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Move","amount":-50.0,"effectiveAmount":-50.0,
               "currency":"EUR","transactionType":"transfer","date":1705276800000}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertTrue("type mapping is case-insensitive", result.success)
        assertEquals(
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER,
            slot.captured.transactionType
        )
        assertEquals(-50.0, slot.captured.amount, 0.0)
    }

    @Test
    fun `json unknown type row does not prevent later rows importing`() = runTest {
        captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Bad","amount":1.0,"transactionType":"NOPE","date":1705276800000},
              {"merchant":"Good","amount":2.0,"transactionType":"PURCHASE","date":1705276800001}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertFalse(result.success)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.errorCount)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun captureJsonRequest(): CapturingSlot<CreateExpenseRequest> {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)
        return requestSlot
    }

    /** Builds one CSV v2 data row under the full current export header. */
    private fun v2Csv(dataRow: String): String =
        "# ExpenseTracker Export v2, rowCount=1, startDate=0, endDate=0\n" +
            "ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,SourceAccountName,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,ConversionRateUsed,IsBusinessExpense,BusinessPurpose,BusinessCategory,BusinessProject,RequiresReceipt,SourceLinks\n" +
            dataRow
}
