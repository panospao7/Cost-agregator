package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.AnalyticsEngineTestBase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.export.AccountantReportPdfExporter
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Portable repository-routing contract: forwards every row and writes exactly
 * the exporter result. Real PDF bytes are verified separately by
 * AccountingExportPdfRepositoryInstrumentedTest; no fabricated PDF header here.
 */
class AccountingExportPdfRepositoryTest : AnalyticsEngineTestBase() {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun releaseFileProviderMock() {
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun `PDF export delegates all rows and preserves exporter bytes`() = runTest {
        val expenseRepository = mockk<ExpenseRepository>()
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        val pdfExporter = mockk<AccountantReportPdfExporter>()
        val repository = AccountingExportRepository(
            categoryRepository = categoryRepository,
            deterministicExpenseExportPager = DeterministicExpenseExportPager(expenseRepository),
            accountingExportPolicy = AccountingExportPolicy(),
            quickBooksExporter = QuickBooksIIFExporter(),
            xeroExporter = XeroCSVExporter(),
            freshBooksExporter = FreshBooksExporter(),
            accountantReportPdfExporter = pdfExporter,
            timeProvider = timeProvider,
            readBarrier = mockk(relaxed = true),
            privacyGate = privacyGate
        )
        val root = temporaryFolder.newFolder("pdf-export")
        val context = mockk<Context>()
        every { context.filesDir } returns root
        every { context.cacheDir } returns root
        every { context.packageName } returns "com.yourname.expensetracker.test"
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns
            mockk<Uri>()

        val start = ms("2026-03-01")
        val end = ms("2026-04-01")
        val pageSize = DeterministicExpenseExportPager.EXPORT_PAGE_SIZE
        val expenses = listOf(
            Expense(
                id = 1L,
                amount = 650.0,
                currency = "EUR",
                merchant = "Laptop Store",
                transactionType = TransactionType.PURCHASE,
                date = start + 1_000L,
                categoryId = 1L
            ),
            Expense(
                id = 2L,
                amount = 85.0,
                currency = "EUR",
                merchant = "Client Lunch",
                transactionType = TransactionType.PURCHASE,
                date = start + 2_000L,
                categoryId = 2L
            )
        )

        coEvery {
            expenseRepository.getExpensesBetweenForExportKeyset(
                start, end, pageSize, null, null
            )
        } returns expenses
        val serializedBytes = "exporter-payload-for-routing-test".toByteArray(Charsets.UTF_8)
        every { pdfExporter.export(expenses, any(), start, end) } returns serializedBytes

        val result = repository.exportExpenses(context, start, end, ExportFormat.ACCOUNTANT_REPORT_PDF)

        assertTrue("PDF export must succeed", result.success)
        assertEquals(expenses.size, result.recordCount)
        assertTrue(result.filePath.orEmpty().endsWith(".pdf"))

        assertArrayEquals(serializedBytes, File(result.filePath!!).readBytes())
        verify(exactly = 1) { pdfExporter.export(expenses, any(), start, end) }
    }

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
