package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.BusinessExpenseRepository
import com.yourname.expensetracker.data.repository.TaxSettingsRepository
import com.yourname.expensetracker.domain.business.BusinessExpenseReportGenerator
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.util.CsvExpenseImporter
import com.yourname.expensetracker.util.JsonExpenseImporter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * loan-installments S9 (OD-11, INV-1): flagged rows are exported with a marker in the existing notes
 * field, export totals follow the canonical predicate, no plan data is written, and re-import ignores
 * the marker.
 */
class ExpenseExportInstallmentMarkingTest {

    private val day = 1_700_000_000_000L

    private fun expense(
        id: Long,
        amount: Double,
        merchant: String = "Store",
        notes: String? = null,
        reason: SpendingExclusionReason? = null,
        isBusiness: Boolean = false
    ) = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = day,
        notes = notes,
        isBusinessExpense = isBusiness,
        spendingExclusionReason = reason?.name
    )

    private val e1 = expense(1L, 600.0, "Electronics", reason = SpendingExclusionReason.INSTALLMENT_PURCHASE)
    private val installment = expense(2L, 50.0, "Electronics")
    private val reversed = expense(3L, 50.0, "Electronics", reason = SpendingExclusionReason.INSTALLMENT_REVERSED)
    private val plain = expense(4L, 40.0, "Cafe", notes = "lunch")

    @Test
    fun `flagged purchase is exported with the not counted marker in notes`() {
        val tx = e1.toExportTransaction()

        assertThat(tx.notes).isEqualTo(ExportInstallmentMarker.NOT_COUNTED)
        assertThat(tx.notes).isEqualTo("[not counted (installment plan)]")
        assertThat(tx.merchant).isEqualTo("Electronics")
        assertThat(tx.amount).isEqualTo(600.0)
        assertThat(tx.effectiveAmount).isEqualTo(0.0)
    }

    @Test
    fun `reversed installment is marked refunded and existing notes are kept`() {
        val tx = expense(
            3L, 50.0, notes = "keep me", reason = SpendingExclusionReason.INSTALLMENT_REVERSED
        ).toExportTransaction()

        assertThat(tx.notes).isEqualTo("[refunded] keep me")
        assertThat(tx.effectiveAmount).isEqualTo(0.0)
        assertThat(reversed.toExportTransaction().notes).isEqualTo(ExportInstallmentMarker.REFUNDED)
    }

    @Test
    fun `non flagged expense is unchanged`() {
        val tx = plain.toExportTransaction()

        assertThat(tx.notes).isEqualTo("lunch")
        assertThat(tx.effectiveAmount).isEqualTo(40.0)
        assertThat(installment.toExportTransaction().notes).isNull()
        assertThat(ExportInstallmentMarker.notesWithMarker("n", null)).isEqualTo("n")
    }

    @Test
    fun `export transaction totals equal the canonical predicate totals and exclude 600`() {
        val rows = listOf(e1, installment, reversed, plain)

        val exportTotal = rows.map { it.toExportTransaction() }.sumOf { it.effectiveAmount }
        val canonicalTotal = rows.filter { it.countsAsSpending }.sumOf { it.effectiveAmount }

        assertThat(exportTotal).isEqualTo(canonicalTotal)
        assertThat(exportTotal).isEqualTo(90.0)
    }

    @Test
    fun `quickbooks iif omits flagged rows and leaves unflagged rows unchanged`() {
        val rows = listOf(e1, installment, reversed, plain).map { it.toExportTransaction() }
        val categories = emptyMap<Long, String>()

        val iif = QuickBooksIIFExporter().export(rows, categories)
        val unflaggedOnly = QuickBooksIIFExporter().export(
            listOf(installment, plain).map { it.toExportTransaction() }, categories
        )

        assertThat(iif).isEqualTo(unflaggedOnly)
        // Flagged rows are absent entirely: no marker text, no 600.00 amount, no plan data.
        assertThat(iif).doesNotContain(ExportInstallmentMarker.NOT_COUNTED)
        assertThat(iif).doesNotContain(ExportInstallmentMarker.REFUNDED)
        assertThat(iif).doesNotContain("600")
        assertThat(iif.lowercase()).doesNotContain("lender")
        assertThat(iif.lowercase()).doesNotContain("schedule")
        // Two unflagged transactions -> two TRNS blocks.
        assertThat(iif.lines().count { it.startsWith("TRNS\t") }).isEqualTo(2)
        assertThat(QuickBooksIIFExporter().export(listOf(e1.toExportTransaction()), categories)
            .lines().count { it.startsWith("TRNS\t") }).isEqualTo(0)
    }

    @Test
    fun `isInstallmentFlagged is the single rule for expense and export transaction`() {
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(e1)).isTrue()
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(reversed)).isTrue()
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(installment)).isFalse()
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(plain)).isFalse()
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(e1.toExportTransaction())).isTrue()
        assertThat(ExportInstallmentMarker.isInstallmentFlagged(plain.toExportTransaction())).isFalse()
    }

    @Test
    fun `isMarkedNotes detects both markers and nothing else`() {
        assertThat(ExportInstallmentMarker.isMarkedNotes(e1.toExportTransaction().notes)).isTrue()
        assertThat(ExportInstallmentMarker.isMarkedNotes(reversed.toExportTransaction().notes)).isTrue()
        assertThat(ExportInstallmentMarker.isMarkedNotes("  [refunded] keep me")).isTrue()
        assertThat(ExportInstallmentMarker.isMarkedNotes("lunch")).isFalse()
        assertThat(ExportInstallmentMarker.isMarkedNotes("lunch [refunded]")).isFalse()
        assertThat(ExportInstallmentMarker.isMarkedNotes(null)).isFalse()
    }

    @Test
    fun `xero export omits flagged rows and leaves unflagged rows unchanged`() {
        val rows = listOf(e1, installment, reversed, plain).map { it.toExportTransaction() }
        val categories = emptyMap<Long, String>()

        val csv = XeroCSVExporter().export(rows, categories)
        val unflaggedOnly = XeroCSVExporter().export(
            listOf(installment, plain).map { it.toExportTransaction() }, categories
        )

        assertThat(csv).isEqualTo(unflaggedOnly)
        // Header + the two unflagged rows only; flagged ids 1 and 3 never appear as references.
        assertThat(csv.trimEnd().lines()).hasSize(3)
        assertThat(csv).doesNotContain("600")
        assertThat(csv).doesNotContain(ExportInstallmentMarker.NOT_COUNTED)
        assertThat(csv).doesNotContain(ExportInstallmentMarker.REFUNDED)
    }

    @Test
    fun `freshbooks export omits flagged rows and leaves unflagged rows unchanged`() {
        val rows = listOf(e1, installment, reversed, plain).map { it.toExportTransaction() }
        val categories = emptyMap<Long, String>()

        val csv = FreshBooksExporter().export(rows, categories)
        val unflaggedOnly = FreshBooksExporter().export(
            listOf(installment, plain).map { it.toExportTransaction() }, categories
        )

        assertThat(csv).isEqualTo(unflaggedOnly)
        assertThat(csv.trimEnd().lines()).hasSize(3)
        assertThat(csv).doesNotContain("600")
    }

    @Test
    fun `csv re-import skips marked rows, reports them skipped and imports unflagged rows normally`() = runTest {
        val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        val categoryDao = mockk<CategoryDao>(relaxed = true)
        val currency = mockk<CurrencySettingsRepository>()
        coEvery { currency.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val captured = mutableListOf<CreateExpenseRequest>()
        // The importers call the legacy createExpense (suppressed there too; migration is a separate TODO).
        @Suppress("DEPRECATION_ERROR")
        coEvery { coordinator.createExpense(capture(captured)) } returns CreateExpenseResult.Created(1L)
        val maintenance = mockk<RestoreMaintenanceMode>()
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenance.isWritesAllowed() } returns true
        val importer = CsvExpenseImporter(
            categoryDao,
            coordinator,
            currencySettingsRepository = currency,
            writeBarrier = DatabaseWriteBarrier(maintenance)
        )

        val result = importer.importFromContent(
            "date,merchant,amount,category,notes\n" +
                "2024-01-15,Electronics,600.00,Shopping,${ExportInstallmentMarker.NOT_COUNTED}\n" +
                "2024-01-16,Electronics,50.00,Shopping,${ExportInstallmentMarker.REFUNDED} keep me\n" +
                "2024-01-17,Cafe,40.00,Food,lunch"
        )

        assertThat(result).isInstanceOf(CsvExpenseImporter.ImportResult.Success::class.java)
        val success = result as CsvExpenseImporter.ImportResult.Success
        assertThat(success.imported).isEqualTo(1)
        assertThat(success.duplicates).isEqualTo(2) // skipped counter
        assertThat(success.errors).isEqualTo(0)
        assertThat(captured.map { it.merchant }).containsExactly("Cafe")
        assertThat(captured.single().notes).isEqualTo("lunch")
        // The import request has no spending exclusion input, so a re-import cannot create a flag.
        assertThat(CreateExpenseRequest::class.java.declaredFields.map { it.name.lowercase() })
            .containsNoneIn(listOf("spendingexclusionreason"))
    }

    @Test
    fun `json re-import skips marked rows in v2 and v1 and imports unflagged rows normally`() = runTest {
        val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
        val categoryDao = mockk<CategoryDao>(relaxed = true)
        val time = mockk<TimeProvider>(relaxed = true)
        every { time.now() } returns day
        val captured = mutableListOf<CreateExpenseRequest>()
        // The importers call the legacy createExpense (suppressed there too; migration is a separate TODO).
        @Suppress("DEPRECATION_ERROR")
        coEvery { coordinator.createExpense(capture(captured)) } returns CreateExpenseResult.Created(1L)
        val maintenance = mockk<RestoreMaintenanceMode>()
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenance.isWritesAllowed() } returns true
        val importer = JsonExpenseImporter(coordinator, categoryDao, time, DatabaseWriteBarrier(maintenance))

        for (version in listOf(2, 1)) {
            captured.clear()
            val result = importer.importFromContent(
                """{"schemaVersion": $version, "rows": [
                    {"id": 1, "merchant": "Electronics", "amount": 600.0, "currency": "EUR", "date": $day,
                     "notes": "${ExportInstallmentMarker.NOT_COUNTED}"},
                    {"id": 2, "merchant": "Electronics", "amount": 50.0, "currency": "EUR", "date": $day,
                     "notes": "${ExportInstallmentMarker.REFUNDED} keep me"},
                    {"id": 3, "merchant": "Cafe", "amount": 40.0, "currency": "EUR", "date": $day,
                     "notes": "lunch"}
                ]}"""
            )

            assertThat(result.importedCount).isEqualTo(1)
            assertThat(result.skippedCount).isEqualTo(2)
            assertThat(result.errorCount).isEqualTo(0)
            assertThat(captured.map { it.merchant }).containsExactly("Cafe")
            assertThat(captured.single().notes).isEqualTo("lunch")
        }
    }

    @Test
    fun `accountant report totals follow the canonical predicate and flagged rows are summarised`() {
        val exporter = AccountantReportPdfExporter(mockk<TimeProvider>(relaxed = true), mockk(), mockk())
        val rows = listOf(e1, installment, reversed, plain)

        val sections = exporter.generateSourceSections(rows, emptyMap())

        assertThat(sections).hasSize(1)
        assertThat(sections.single().total.displayAmount)
            .isEqualTo(rows.filter { it.countsAsSpending }.sumOf { it.effectiveAmount })
        assertThat(sections.single().total.displayAmount).isEqualTo(90.0)
        assertThat(exporter.installmentMarkerSummaryLines(rows)).containsExactly(
            "Transactions marked [not counted (installment plan)]: 1 (excluded from totals)",
            "Transactions marked [refunded]: 1 (excluded from totals)"
        )
        assertThat(exporter.installmentMarkerSummaryLines(listOf(installment, plain))).isEmpty()
    }

    @Test
    fun `business report totals exclude flagged rows and the csv marks them`() = runTest {
        val repo = mockk<BusinessExpenseRepository>(relaxed = true)
        val tax = mockk<TaxSettingsRepository>(relaxed = true)
        val time = mockk<TimeProvider>(relaxed = true)
        every { time.now() } returns day
        every { tax.getFilingCurrency() } returns "EUR"
        val rows = listOf(
            expense(1L, 600.0, "Electronics", reason = SpendingExclusionReason.INSTALLMENT_PURCHASE, isBusiness = true),
            expense(2L, 50.0, "Electronics", isBusiness = true),
            expense(3L, 50.0, "Electronics", reason = SpendingExclusionReason.INSTALLMENT_REVERSED, isBusiness = true)
        )
        coEvery { repo.getBusinessExpenses(any(), any()) } returns rows
        coEvery { repo.getExpensesMissingReceipts(any(), any()) } returns emptyList()
        coEvery { repo.getBusinessMileageBetween(any(), any()) } returns emptyList()
        val generator = BusinessExpenseReportGenerator(repo, time, tax, Dispatchers.Unconfined)

        val report = generator.generateReport(day - 1, day + 1, includeMileage = false)
        val csv = generator.generateCSVExport(day - 1, day + 1, includeMileage = false)

        assertThat(report.totalExpenses).isEqualTo(rows.filter { it.countsAsSpending }.sumOf { it.effectiveAmount })
        assertThat(report.totalExpenses).isEqualTo(50.0)
        assertThat(csv).contains(ExportInstallmentMarker.NOT_COUNTED)
        assertThat(csv).contains(ExportInstallmentMarker.REFUNDED)
    }
}
