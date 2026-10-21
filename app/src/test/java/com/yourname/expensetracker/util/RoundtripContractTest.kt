package com.yourname.expensetracker.util

import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.TransactionType
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
 *
 * Near-duplicate cases are consolidated into row tables; every row keeps a name
 * that is included in its failure message.
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

    private data class CsvTypeRow(val name: String, val csv: String, val expectedType: TransactionType)

    private data class CsvAmountRow(val name: String, val dataRow: String, val expectedAmount: Double)

    private data class JsonRow(
        val name: String,
        val rowJson: String,
        val expectedAmount: Double,
        val expectedType: TransactionType?
    )

    // ── CSV: exact-enum transactionType mapping ─────────────────────────────

    @Test
    fun `csv transaction type mapping table`() = runTest {
        val rows = listOf(
            CsvTypeRow(
                "deposit type maps to exact enum",
                v2Csv("1,2024-01-15,2024-01-15,Cash In,100.00,100.00,EUR,DEPOSIT,Food,,,,"),
                TransactionType.DEPOSIT
            ),
            CsvTypeRow(
                "transfer type maps to exact enum",
                v2Csv("1,2024-01-15,2024-01-15,Move,50.00,50.00,EUR,TRANSFER,Food,,,,"),
                TransactionType.TRANSFER
            ),
            // Legacy schema: no TransactionType column at all.
            CsvTypeRow(
                "missing transaction type defaults to PURCHASE",
                "date,amount,merchant,category,notes\n2024-01-15,12.50,Starbucks,Food,x",
                TransactionType.PURCHASE
            )
        )
        assertEquals("csv transaction type mapping table size", 3, rows.size)
        rows.forEach { row ->
            val slot = captureCsvRequest()
            val result = csvImporter.importFromContent(row.csv) as CsvExpenseImporter.ImportResult.Success

            assertEquals("${row.name}: imported", 1, result.imported)
            assertEquals("${row.name}: errors", 0, result.errors)
            assertEquals("${row.name}: type", row.expectedType, slot.captured.transactionType)
        }
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

    // ── CSV: amount-first precedence with effectiveAmount fallback ──────────

    @Test
    fun `csv amount precedence table`() = runTest {
        val rows = listOf(
            // amount is the ORIGINAL amount; effectiveAmount (40.00) is fallback-only.
            CsvAmountRow(
                "ordinary row uses original amount not effective",
                "1,2024-01-15,2024-01-15,Dinner,80.00,40.00,EUR,PURCHASE,Food,,,,",
                80.00
            ),
            // Exported shared row: effectiveAmount (22.50) reflects the share
            // reduction, but ownership metadata is not exported — import keeps the
            // original amount and the entity recomputes effective = amount.
            CsvAmountRow(
                "shared row keeps original amount as documented unsupported default",
                "1,2024-01-15,2024-01-15,Pizza,45.00,22.50,EUR,PURCHASE,Food,,,,",
                45.00
            ),
            CsvAmountRow(
                "negative refund-style amount roundtrips verbatim",
                "1,2024-01-15,2024-01-15,Refund,-25.50,-25.50,EUR,PURCHASE,Food,,,,",
                -25.50
            ),
            CsvAmountRow(
                "zero boundary amount roundtrips exactly",
                "1,2024-01-15,2024-01-15,Zero,0.0,0.0,EUR,PURCHASE,Food,,,,",
                0.0
            ),
            CsvAmountRow(
                "large boundary amount roundtrips exactly",
                "2,2024-01-16,2024-01-16,Grail,99999999.99,99999999.99,EUR,PURCHASE,Food,,,,",
                99999999.99
            ),
            CsvAmountRow(
                "falls back to effectiveAmount only when amount is missing",
                "1,2024-01-15,2024-01-15,NoAmount,,33.25,EUR,PURCHASE,Food,,,,",
                33.25
            ),
            CsvAmountRow(
                "falls back to effectiveAmount when amount is unparseable",
                "1,2024-01-15,2024-01-15,BadAmount,not-a-number,7.75,EUR,PURCHASE,Food,,,,",
                7.75
            )
        )
        assertEquals("csv amount precedence table size", 7, rows.size)
        rows.forEach { row ->
            val slot = captureCsvRequest()
            val result = csvImporter.importFromContent(v2Csv(row.dataRow)) as CsvExpenseImporter.ImportResult.Success

            assertEquals("${row.name}: imported", 1, result.imported)
            assertEquals("${row.name}: amount", row.expectedAmount, slot.captured.amount, 0.0)
        }
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
    fun `json type and amount precedence table`() = runTest {
        val rows = listOf(
            JsonRow(
                "deposit row maps exact enum and original amount",
                """{"merchant":"Salary","amount":1000.0,"effectiveAmount":1000.0,
                   "currency":"EUR","transactionType":"DEPOSIT","date":1705276800000}""",
                1000.0,
                TransactionType.DEPOSIT
            ),
            // CSV and JSON must share the amount-first precedence.
            JsonRow(
                "shared row keeps original amount - parity with csv",
                """{"merchant":"Pizza","amount":45.0,"effectiveAmount":22.5,
                   "currency":"EUR","transactionType":"PURCHASE","date":1705276800000}""",
                45.0,
                null
            ),
            JsonRow(
                "falls back to effectiveAmount when amount absent - parity with csv",
                """{"merchant":"NoAmount","effectiveAmount":33.25,
                   "currency":"EUR","transactionType":"PURCHASE","date":1705276800000}""",
                33.25,
                null
            ),
            // Type mapping is case-insensitive.
            JsonRow(
                "lowercase transfer row maps exact enum and negative amount",
                """{"merchant":"Move","amount":-50.0,"effectiveAmount":-50.0,
                   "currency":"EUR","transactionType":"transfer","date":1705276800000}""",
                -50.0,
                TransactionType.TRANSFER
            )
        )
        assertEquals("json type and amount precedence table size", 4, rows.size)
        rows.forEach { row ->
            val slot = captureJsonRequest()
            val result = jsonImporter.importFromContent("""{"schemaVersion":2,"rows":[${row.rowJson}]}""")

            assertTrue("${row.name}: success", result.success)
            assertEquals("${row.name}: amount", row.expectedAmount, slot.captured.amount, 0.0)
            if (row.expectedType != null) {
                assertEquals("${row.name}: type", row.expectedType, slot.captured.transactionType)
            }
        }
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
    fun `json unknown transaction type fails row with UNKNOWN_TRANSACTION_TYPE and later rows still import`() = runTest {
        val slot = captureJsonRequest()
        val json = """
            {"schemaVersion":2,"rows":[
              {"merchant":"Mystery","amount":10.0,
               "currency":"EUR","transactionType":"BARter","date":1705276800000},
              {"merchant":"Good","amount":2.0,"transactionType":"PURCHASE","date":1705276800001}
            ]}
        """.trimIndent()

        val result = jsonImporter.importFromContent(json)

        assertFalse(result.success)
        assertEquals(1, result.errorCount)
        assertEquals(
            "Row 0: ${ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE}",
            result.errors.single()
        )
        assertEquals(1, result.importedCount)
        assertEquals(2.0, slot.captured.amount, 0.0)
        // Only the good row reaches the coordinator; the unknown-type row never does.
        coVerify(exactly = 1) { coordinator.createExpense(any()) }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun captureCsvRequest(): CapturingSlot<CreateExpenseRequest> {
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)
        return requestSlot
    }

    private fun captureJsonRequest(): CapturingSlot<CreateExpenseRequest> = captureCsvRequest()

    /** Builds one CSV v2 data row under the full current export header. */
    private fun v2Csv(dataRow: String): String =
        "# ExpenseTracker Export v2, rowCount=1, startDate=0, endDate=0\n" +
            "ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,SourceAccountName,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,ConversionRateUsed,IsBusinessExpense,BusinessPurpose,BusinessCategory,BusinessProject,RequiresReceipt,SourceLinks\n" +
            dataRow
}
