package com.yourname.expensetracker.util

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * RP-19 (19-D): ImportCoordinator is the canonical import facade (retained per
 * D4). These tests pin the repaired contract: per-row errors from the CSV leg
 * surface in [ImportResult.errors] with the `Row <n>: <reason>` convention
 * (parity with the JSON leg), success is false when any row fails, and
 * imported expense IDs are propagated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class ImportCoordinatorTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>()
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    private lateinit var importCoordinator: ImportCoordinator

    @Before
    fun setup() {
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
        val barrier = DatabaseWriteBarrier(maintenanceMode)
        importCoordinator = ImportCoordinator(
            csvImporter = CsvExpenseImporter(
                categoryDao,
                coordinator,
                currencySettingsRepository = currencySettingsRepository,
                writeBarrier = barrier
            ),
            jsonImporter = JsonExpenseImporter(
                coordinator,
                categoryDao,
                FakeTimeProvider(1_712_000_000_000L),
                barrier
            )
        )
        coEvery { categoryDao.getByName(any()) } returns Category(id = 1, name = "Food", icon = "F", color = "#112233")
    }

    @Test
    fun `csv leg surfaces per-row errors with row indices`() = runTest {
        coEvery { coordinator.createExpense(any()) } returns
            CreateExpenseResult.Created(101L) andThen
            CreateExpenseResult.ValidationFailed(listOf("amount must be positive"))

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,10.00,Good,Food,x\n" +
            "2024-01-16,0.00,Bad,Food,x"

        val result = importCoordinator.importFromContent(csv)

        assertThat(result.success).isFalse()
        assertThat(result.importedCount).isEqualTo(1)
        assertThat(result.errorCount).isEqualTo(1)
        assertThat(result.errors).containsExactly("Row 1: Validation: amount must be positive")
        assertThat(result.expenseIds).containsExactly(101L)
    }

    @Test
    fun `csv unknown transaction type surfaces the controlled code through the facade`() = runTest {
        val csv = "# ExpenseTracker Export v2, rowCount=1\n" +
            "ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,SourceAccountName,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,ConversionRateUsed,IsBusinessExpense,BusinessPurpose,BusinessCategory,BusinessProject,RequiresReceipt,SourceLinks\n" +
            "1,2024-01-15,2024-01-15,Mystery,10.00,10.00,EUR,NOT_A_TYPE,Food,,,,"

        val result = importCoordinator.importFromContent(csv)

        assertThat(result.success).isFalse()
        assertThat(result.errors).containsExactly("Row 0: ${ImportContractErrorCodes.UNKNOWN_TRANSACTION_TYPE}")
        assertThat(result.importedCount).isEqualTo(0)
    }

    @Test
    fun `all-good csv import reports success with expense ids and no errors`() = runTest {
        coEvery { coordinator.createExpense(any()) } returns
            CreateExpenseResult.Created(1L) andThen CreateExpenseResult.Created(2L)

        val csv = "date,amount,merchant,category,notes\n" +
            "2024-01-15,10.00,One,Food,x\n" +
            "2024-01-16,20.00,Two,Food,y"

        val result = importCoordinator.importFromContent(csv)

        assertThat(result.success).isTrue()
        assertThat(result.importedCount).isEqualTo(2)
        assertThat(result.errorCount).isEqualTo(0)
        assertThat(result.errors).isEmpty()
        assertThat(result.expenseIds).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `duplicates count as skipped and never as failures`() = runTest {
        coEvery { coordinator.createExpense(any()) } returns
            CreateExpenseResult.DuplicateSkipped(existingExpenseId = 9L, reason = "dup")

        val result = importCoordinator.importFromContent(
            "date,amount,merchant,category,notes\n2024-01-15,10.00,One,Food,x"
        )

        assertThat(result.success).isTrue()
        assertThat(result.skippedCount).isEqualTo(1)
        assertThat(result.errorCount).isEqualTo(0)
    }

    @Test
    fun `json leg keeps surfacing per-row errors (parity)`() = runTest {
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(5L)

        val result = importCoordinator.importFromContent(
            """{"schemaVersion":2,"rows":[{"merchant":"A","amount":1.0,"date":1705276800000}]}"""
        )

        assertThat(result.success).isTrue()
        assertThat(result.importedCount).isEqualTo(1)
        assertThat(result.expenseIds).containsExactly(5L)
    }

    @Test
    fun `unrecognized content fails with a controlled message`() = runTest {
        val result = importCoordinator.importFromContent("just some text")

        assertThat(result.success).isFalse()
        assertThat(result.errorCount).isEqualTo(1)
        assertThat(result.errors).containsExactly("Unrecognized import format")
    }

    @Test
    fun `detectFormat classifies v2 json vs v1 json vs csv`() {
        assertThat(importCoordinator.detectFormat("""{"schemaVersion":2,"rows":[]}"""))
            .isEqualTo(ImportFormat.JSON_V2)
        assertThat(importCoordinator.detectFormat("""{"schemaVersion":1,"rows":[]}"""))
            .isEqualTo(ImportFormat.JSON_V1)
        assertThat(importCoordinator.detectFormat("date,amount\n2024-01-01,1.0"))
            .isEqualTo(ImportFormat.CSV_LEGACY)
        assertThat(importCoordinator.detectFormat("\uFEFF{\"schemaVersion\":2,\"rows\":[]}"))
            .isEqualTo(ImportFormat.JSON_V2)
    }
}
