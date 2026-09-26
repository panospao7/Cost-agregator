package com.yourname.expensetracker.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.export.AccountantReportPdfExporter
import com.yourname.expensetracker.domain.export.AccountingExportPolicy
import com.yourname.expensetracker.domain.export.FreshBooksExporter
import com.yourname.expensetracker.domain.export.QuickBooksIIFExporter
import com.yourname.expensetracker.domain.export.XeroCSVExporter
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.database.entity.Category
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real PDF serialization and repository output, moved from the JVM-only suite.
 * Requires an Android device/emulator; the resolved desktop native runtime does
 * not support Windows. The original success/count/path/nonempty/%PDF assertions
 * are retained here, not replaced by a mocked PDF header in a unit test.
 */
@RunWith(AndroidJUnit4::class)
class AccountingExportPdfRepositoryInstrumentedTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(ApplicationProvider.getApplicationContext<Context>().cacheDir)

    @After
    fun releaseFileProviderMock() {
        unmockkStatic(FileProvider::class)
    }

    @Test
    fun exportExpensesAccountantReportPdfWritesPdfOutput() = runTest {
        val timeProvider = object : TimeProvider {
            override fun now(): Long = ms("2026-04-01")
        }
        val categoryRepository = mockk<CategoryRepository>()
        every { categoryRepository.allCategories } returns flowOf(listOf(
            Category(id = 1L, name = "Food & Dining", icon = "F", color = "#FF5733"),
            Category(id = 2L, name = "Groceries", icon = "G", color = "#33FF57")
        ))
        val expenseRepository = mockk<ExpenseRepository>()
        val privacyGate = mockk<PrivacyGate>()
        coEvery { privacyGate.check(any(), any()) } returns PrivacyDecision.Allowed
        val currencySettings = mockk<CurrencySettingsRepository>()
        every { currencySettings.homeCurrency() } returns flowOf("EUR")
        coEvery { currencySettings.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        val repository = AccountingExportRepository(
            categoryRepository = categoryRepository,
            deterministicExpenseExportPager = DeterministicExpenseExportPager(expenseRepository),
            accountingExportPolicy = AccountingExportPolicy(),
            quickBooksExporter = QuickBooksIIFExporter(),
            xeroExporter = XeroCSVExporter(),
            freshBooksExporter = FreshBooksExporter(),
            accountantReportPdfExporter = AccountantReportPdfExporter(
                timeProvider, mockk<CurrencyConverter>(), currencySettings
            ),
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
            Uri.parse("content://com.yourname.expensetracker.test/exports/report.pdf")

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

        val result = repository.exportExpenses(context, start, end, ExportFormat.ACCOUNTANT_REPORT_PDF)

        assertTrue("PDF export must succeed", result.success)
        assertEquals(expenses.size, result.recordCount)
        assertTrue(result.filePath.orEmpty().endsWith(".pdf"))

        val pdfBytes = File(result.filePath!!).readBytes()
        assertTrue("PDF file must not be empty", pdfBytes.isNotEmpty())
        val header = pdfBytes.copyOfRange(0, minOf(4, pdfBytes.size)).toString(Charsets.US_ASCII)
        assertEquals("%PDF", header)
    }

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
