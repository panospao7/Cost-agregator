package com.yourname.expensetracker.util

import com.google.common.truth.Truth.assertThat
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * J-12-2 (part 1): preview/import parity, write-freedom of preview, bounded
 * failure codes, cooperative cancellation, read-only category resolution and
 * progress monotonicity. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class CsvExpenseImporterPreviewTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private val coffee = Category(id = 5, name = "Coffee", icon = "C", color = "#FF0000")
    private val uncategorized = Category(id = 9, name = "Uncategorized", icon = "?", color = "#BDBDBD")

    private fun barrier(mode: RestoreMaintenanceMode.Mode): DatabaseWriteBarrier {
        every { maintenanceMode.currentMode() } returns mode
        every { maintenanceMode.isWritesAllowed() } returns (mode == RestoreMaintenanceMode.Mode.NORMAL)
        return DatabaseWriteBarrier(maintenanceMode)
    }

    private fun newImporter(
        mode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL
    ) = CsvExpenseImporter(
        categoryDao,
        coordinator,
        currencySettingsRepository = currencySettingsRepository,
        writeBarrier = barrier(mode)
    )

    private lateinit var importer: CsvExpenseImporter

    @Before
    fun setup() {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { categoryDao.getByName(any()) } returns null
        coEvery { categoryDao.getByName("Coffee") } returns coffee
        coEvery { categoryDao.getByName("Uncategorized") } returns uncategorized
        var nextId = 100L
        coEvery { coordinator.createExpense(any()) } answers {
            CreateExpenseResult.Created(nextId++)
        }
        importer = newImporter()
    }

    // BOM, 8 data rows: 4 valid (1 unknown category), bad date, bad amount,
    // missing merchant, installment-marked. Row 6 carries a formula-like cell,
    // row 7 a quoted comma and a quoted embedded newline.
    private val mixedCsv = "﻿date,amount,merchant,category,notes\n" +
        "2024-01-15,25.50,Starbucks,Coffee,ok\n" +
        "2024-13-45,1.00,BadDate,Coffee,x\n" +
        "2024-01-16,abc,BadAmount,Coffee,x\n" +
        "2024-01-17,5.00,Shop,NewCat,x\n" +
        "2024-01-18,7.00,Inst,Coffee,[not counted (installment plan)]\n" +
        "2024-01-19,2.00,=1+1,Coffee,x\n" +
        "2024-01-20,3.00,\"Cafe, Bar\",Coffee,\"line1\nline2\"\n" +
        "2024-01-21,1.00,,Coffee,x\n"

    // ---- 1. preview/import parity ------------------------------------------------

    @Test
    fun `preview counts equal what import then does for a mixed CSV`() = runTest {
        val preview = importer.preview(mixedCsv)

        assertThat(preview.headerFailure).isNull()
        assertThat(preview.totalRows).isEqualTo(8)
        assertThat(preview.validRows).isEqualTo(4)
        assertThat(preview.invalidRowsByCode).containsExactly(
            CsvImportFailureCode.INVALID_DATE, 1,
            CsvImportFailureCode.INVALID_AMOUNT, 1,
            CsvImportFailureCode.MISSING_COLUMN, 1
        )
        assertThat(preview.invalidRows).isEqualTo(3)
        assertThat(preview.unknownCategoryRows).isEqualTo(1)
        assertThat(preview.skippedInstallmentRows).isEqualTo(1)

        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } answers {
            CreateExpenseResult.Created(100L + requests.size)
        }
        val result = importer.importFromContent(mixedCsv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(preview.validRows)
        assertThat(result.errors).isEqualTo(preview.invalidRows)
        assertThat(result.duplicates).isEqualTo(preview.skippedInstallmentRows)
        assertThat(result.perRowResults).hasSize(preview.totalRows)
        assertThat(requests).hasSize(preview.validRows)
        assertThat(requests.count { it.categoryId == uncategorized.id })
            .isEqualTo(preview.unknownCategoryRows)

        val failed = result.perRowResults.filterIsInstance<CsvExpenseImporter.RowResult.Failed>()
        assertThat(failed.groupingBy { it.code }.eachCount()).isEqualTo(preview.invalidRowsByCode)
        assertThat(failed.map { it.rowNumber }).containsExactly(2, 3, 8).inOrder()
        assertThat(result.perRowResults[4])
            .isEqualTo(CsvExpenseImporter.RowResult.Duplicate("INSTALLMENT_EXPORT_MARKER"))
    }

    @Test
    fun `quoted comma newline and formula-like cells import verbatim as plain text`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        importer.importFromContent(mixedCsv)

        assertThat(requests.map { it.merchant }).containsExactly("Starbucks", "Shop", "=1+1", "Cafe, Bar")
        assertThat(requests.last().notes).isEqualTo("line1\nline2")
    }

    @Test
    fun `preview performs no writes and needs no write barrier admission`() = runTest {
        val blocked = newImporter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)

        val preview = blocked.preview(mixedCsv)

        assertThat(preview.validRows).isEqualTo(4)
        assertThat(preview.headerFailure).isNull()
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
        coVerify(exactly = 0) { categoryDao.insert(any()) }
        coVerify(exactly = 0) { currencySettingsRepository.resolveHomeCurrency() }
    }

    @Test
    fun `preview of a malformed header reports INVALID_HEADER with zero rows`() = runTest {
        val preview = importer.preview("\"date,amount,merchant")

        assertThat(preview.headerFailure).isEqualTo(CsvImportFailureCode.INVALID_HEADER)
        assertThat(preview.totalRows).isEqualTo(0)
        assertThat(preview.validRows).isEqualTo(0)
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }

    @Test
    fun `preview of header-only content is empty and not a failure`() = runTest {
        val preview = importer.preview("date,amount,merchant\n")

        assertThat(preview).isEqualTo(CsvImportPreview(0, 0, emptyMap(), 0, 0))
    }

    // ---- 2. bounded codes ----------------------------------------------------------

    @Test
    fun `no result or preview carries raw cell text or exception messages`() = runTest {
        coEvery { coordinator.createExpense(any()) } answers {
            when (firstArg<CreateExpenseRequest>().merchant) {
                "ThrowMe" -> throw IllegalStateException("SECRET-EXC-123")
                "RejectMe" -> CreateExpenseResult.ValidationFailed(listOf("SECRET-VALID-123"))
                "ErrMe" -> CreateExpenseResult.Error(RuntimeException("SECRET-ERR-123"))
                "ConflictMe" -> CreateExpenseResult.InsertConflict("SECRET-DEDUPE-123")
                else -> CreateExpenseResult.Created(7L)
            }
        }
        val csv = "date,amount,merchant,category,notes,transactionType\n" +
            "2024-99-99,1.00,SECRET-MERCHANT-123,SECRET-CAT-123,SECRET-NOTES-123,\n" +
            "2024-01-01,SECRET-AMT-123,m,c,n,\n" +
            "2024-01-01,1.00,m,c,n,SECRET-TYPE-123\n" +
            "2024-01-01,1.00,ThrowMe,c,n,\n" +
            "2024-01-01,1.00,RejectMe,c,n,\n" +
            "2024-01-01,1.00,ErrMe,c,n,\n" +
            "2024-01-01,1.00,ConflictMe,c,n,\n" +
            "2024-01-01,1.00,SECRET-OK-123,SECRET-CAT2-123,SECRET-NOTES2-123,\n" +
            "2024-01-01,1.00,\"SECRET-UNCLOSED-123"

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success
        val preview = importer.preview(csv)

        assertThat(result.perRowResults.filterIsInstance<CsvExpenseImporter.RowResult.Failed>())
            .containsExactly(
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.INVALID_DATE, 1),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.INVALID_AMOUNT, 2),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.UNKNOWN_TRANSACTION_TYPE, 3),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.INTERNAL, 4),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.ROW_REJECTED, 5),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.INTERNAL, 6),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.ROW_REJECTED, 7),
                CsvExpenseImporter.RowResult.Failed(CsvImportFailureCode.MALFORMED_ROW, 9)
            ).inOrder()
        assertThat(result.perRowResults[7]).isEqualTo(CsvExpenseImporter.RowResult.Imported(7L))

        val rendered = listOf(result.toString(), preview.toString()) +
            result.perRowResults.map { it.toString() }
        rendered.forEach { text ->
            assertThat(text).doesNotContain("SECRET")
        }
    }

    @Test
    fun `top-level error results carry only a bounded code`() = runTest {
        val blocked = newImporter(RestoreMaintenanceMode.Mode.RESTORE_PREPARING)
        val blockedResult = blocked.importFromContent("date,amount,merchant\n2024-01-01,1,SECRET-MERCHANT-123")
        val headerResult = importer.importFromContent("\"SECRET-HEADER-123")
        val headerPreview = importer.preview("\"SECRET-HEADER-123")

        assertThat(blockedResult)
            .isEqualTo(CsvExpenseImporter.ImportResult.Error(CsvImportFailureCode.WRITE_BLOCKED))
        assertThat(headerResult)
            .isEqualTo(CsvExpenseImporter.ImportResult.Error(CsvImportFailureCode.INVALID_HEADER))
        assertThat(headerPreview.toString()).doesNotContain("SECRET")
        assertThat(blockedResult.toString()).doesNotContain("SECRET")
        assertThat(headerResult.toString()).doesNotContain("SECRET")
    }

    // ---- 3. cancellation -------------------------------------------------------------

    @Test
    fun `cancelling between rows stops further createExpense calls and propagates`() = runTest {
        val calls = AtomicInteger(0)
        val jobRef = AtomicReference<Job>()
        coEvery { coordinator.createExpense(any()) } answers {
            if (calls.incrementAndGet() == 2) jobRef.get().cancel()
            CreateExpenseResult.Created(calls.get().toLong())
        }
        val csv = "date,amount,merchant,category\n" +
            (1..5).joinToString("\n") { "2024-01-0$it,1.00,M$it,Coffee" }
        val thrown = AtomicReference<Throwable?>()

        val job = launch {
            try {
                importer.importFromContent(csv)
            } catch (e: CancellationException) {
                thrown.set(e)
                throw e
            }
        }
        jobRef.set(job)
        job.join()

        assertThat(thrown.get()).isInstanceOf(CancellationException::class.java)
        assertThat(job.isCancelled).isTrue()
        // Rows 1 and 2 were created before the cancel and are not compensated; 3..5 never run.
        coVerify(exactly = 2) { coordinator.createExpense(any()) }
        coVerify(exactly = 0) { categoryDao.insert(any()) }
    }

    // ---- 4. category resolution ------------------------------------------------------

    @Test
    fun `unknown category resolves to the Uncategorized id and known category keeps its id`() = runTest {
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        importer.importFromContent(
            "date,amount,merchant,category\n" +
                "2024-01-01,1.00,A,Coffee\n" +
                "2024-01-02,1.00,B,NoSuchCategory\n" +
                "2024-01-03,1.00,C,"
        )

        assertThat(requests.map { it.categoryId }).containsExactly(5L, 9L, 9L).inOrder()
        coVerify(exactly = 0) { categoryDao.insert(any()) }
    }

    @Test
    fun `missing Uncategorized yields null category and still never inserts`() = runTest {
        coEvery { categoryDao.getByName("Uncategorized") } returns null
        val requests = mutableListOf<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requests)) } returns CreateExpenseResult.Created(1L)

        importer.importFromContent(
            "date,amount,merchant,category\n" +
                "2024-01-01,1.00,A,Coffee\n" +
                "2024-01-02,1.00,B,NoSuchCategory\n" +
                "2024-01-03,1.00,C,Uncategorized"
        )

        assertThat(requests.map { it.categoryId }).containsExactly(5L, null, null).inOrder()
        coVerify(exactly = 0) { categoryDao.insert(any()) }
    }

    // ---- 5. progress -----------------------------------------------------------------

    @Test
    fun `import progress is reported as monotonic done and constant total`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()

        importer.importFromContent(mixedCsv) { done, total -> seen.add(done to total) }

        assertThat(seen).isEqualTo((1..8).map { it to 8 })
    }

    @Test
    fun `preview progress is reported as monotonic done and constant total`() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()

        importer.preview(mixedCsv) { done, total -> seen.add(done to total) }

        assertThat(seen).isEqualTo((1..8).map { it to 8 })
    }
}
