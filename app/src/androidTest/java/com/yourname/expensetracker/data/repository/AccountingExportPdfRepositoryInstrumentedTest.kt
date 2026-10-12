package com.yourname.expensetracker.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Real PDF serialization, page rendering and repository output, separate from source-money unit tests.
 * Requires an Android device/emulator; the resolved desktop native runtime does
 * not support Windows. The original success/count/path/nonempty/%PDF assertions
 * are retained here, not replaced by a mocked PDF header in a unit test.
 */
@RunWith(AndroidJUnit4::class)
class AccountingExportPdfRepositoryInstrumentedTest {
    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()

    @get:Rule
    val temporaryFolder = TemporaryFolder(applicationContext.cacheDir)

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
        // Use the real framework context and FileProvider, not native class retransformation.
        val context = applicationContext

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

        // Fixed fixture time gives a deterministic name. Never overwrite an existing file,
        // and clean only these two synthetic artifacts, not the application's export folder.
        val outputFile = File(context.filesDir, "exports/accountant_report_20260401_000000.pdf")
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp_${timeProvider.now()}")
        assertFalse("Synthetic PDF output must not already exist", outputFile.exists())
        assertFalse("Synthetic PDF temporary output must not already exist", tempFile.exists())
        try {
            val result = repository.exportExpenses(context, start, end, ExportFormat.ACCOUNTANT_REPORT_PDF)

            assertTrue("PDF export must succeed", result.success)
            assertEquals(expenses.size, result.recordCount)
            assertTrue(result.filePath.orEmpty().endsWith(".pdf"))
            assertTrue("Synthetic export must use the expected output file", outputFile.absolutePath == result.filePath)

            val pdfBytes = File(result.filePath!!).readBytes()
            assertTrue("PDF file must not be empty", pdfBytes.isNotEmpty())
            val header = pdfBytes.copyOfRange(0, minOf(4, pdfBytes.size)).toString(Charsets.US_ASCII)
            assertEquals("%PDF", header)
            assertEquals("content", result.fileUri!!.scheme)
            applicationContext.contentResolver.openInputStream(result.fileUri!!).use { stream ->
                assertTrue("Export must be readable through the real FileProvider", stream != null)
                assertTrue(pdfBytes.contentEquals(stream!!.readBytes()))
            }
            assertTrue(assertPdfPagesRender(File(result.filePath!!)) > 0)
        } finally {
            outputFile.delete()
            tempFile.delete()
        }
    }

    @Test
    fun mixedCurrencyReportSerializesAndRendersMultiplePagesWithoutFx() {
        val converter = mockk<CurrencyConverter>()
        val exporter = AccountantReportPdfExporter(
            object : TimeProvider { override fun now() = ms("2026-04-01") },
            converter, mockk<CurrencySettingsRepository>()
        )
        val start = ms("2026-03-01")
        val expenses = (1..90).map { index ->
            Expense(
                id = index.toLong(), amount = 600.0 + index,
                currency = listOf("EUR", "USD", "GBP")[(index - 1) % 3],
                merchant = "Synthetic transaction $index", transactionType = TransactionType.PURCHASE,
                date = start + index * 1_000L, categoryId = if (index % 2 == 0) 1L else null
            )
        }
        val pdfFile = temporaryFolder.newFile("mixed-source-report.pdf")
        pdfFile.writeBytes(exporter.export(expenses, mapOf(1L to "Test category"), start, ms("2026-04-01")))
        assertTrue("Report must span more than one page", assertPdfPagesRender(pdfFile) >= 2)
        coVerify(exactly = 0) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    /** Native Android PDF gate: parse and rasterize every page, not just inspect a header. */
    private fun assertPdfPagesRender(pdfFile: File): Int =
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("PDF must contain pages", renderer.pageCount > 0)
                repeat(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        assertTrue("Page dimensions must be positive", page.width > 0 && page.height > 0)
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            assertTrue("Rendered page must contain visible content", pixels.any {
                                Color.alpha(it) > 0 && (it and 0x00ffffff) != 0x00ffffff
                            })
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                renderer.pageCount
            }
        }

    private fun ms(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
