package com.yourname.expensetracker.util

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.export.ExportTransaction
import com.yourname.expensetracker.domain.export.toExportTransaction
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION_ERROR")
class ExportImportRoundtripTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>()
    private val currencyRepo = mockk<CurrencySettingsRepository>()
    private lateinit var importer: CsvExpenseImporter

    @Before
    fun setup() {
        importer = CsvExpenseImporter(categoryDao, coordinator, currencyRepo)
    }

    @Test
    fun exportTransaction_fullJsonRoundtrip_preservesAllFields() {
        val expense = Expense(
            id = 1, amount = 25.50, currency = "EUR", merchant = "Starbucks",
            merchantKey = "starbucks", transactionType = TransactionType.PURCHASE,
            date = 1705276800000L, createdAt = 1705276800000L,
            categoryId = 3, notes = "Morning coffee", baseAmount = 25.50,
            baseCurrency = "EUR", exchangeRateUsed = 1.0,
            isBusinessExpense = false
        )
        val tx = expense.toExportTransaction()
        
        assertEquals(1L, tx.id)
        assertEquals("Starbucks", tx.merchant)
        assertEquals(25.50, tx.amount, 0.01)
        assertEquals("EUR", tx.currency)
        assertEquals("PURCHASE", tx.transactionType.name)
        assertEquals("Morning coffee", tx.notes)
        assertEquals(25.50, tx.baseAmount, 0.01)
        assertEquals(1.0, tx.exchangeRateUsed, 0.001)
    }
    
    @Test
    fun exportTransaction_csvColumns_matchExpectedOrder() {
        val tx = ExportTransaction(
            id = 1, date = 1705276800000L, createdAt = 1705276800000L,
            merchant = "Test", amount = 10.0, effectiveAmount = 10.0,
            currency = "EUR", transactionType = TransactionType.PURCHASE,
            paymentMethod = "CARD", originalCurrency = "EUR",
            homeCurrency = "EUR", baseAmount = 10.0, baseCurrency = "EUR",
            exchangeRateUsed = 1.0, isBusinessExpense = false,
            source = "MANUAL", notes = null, categoryId = null
        )
        assertNotNull(tx.id)
        assertNotNull(tx.merchant)
    }

    @Test
    fun csvV2Roundtrip_parsesExportTransactionFieldsCorrectly() = runTest {
        val cat = Category(id = 3, name = "Coffee", icon = "C", color = "#FF0000")
        coEvery { categoryDao.getByName("Coffee") } returns cat
        coEvery { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val zoneId = ZoneId.systemDefault()
        val tx = ExportTransaction(
            id = 42, date = 1705276800000L, createdAt = 1705363200000L,
            merchant = "Starbucks", amount = 25.50, effectiveAmount = 22.50,
            currency = "EUR", transactionType = TransactionType.PURCHASE,
            paymentMethod = "CARD", originalCurrency = "USD",
            originalAmount = 28.0, homeCurrency = "EUR",
            baseAmount = 25.50, baseCurrency = "EUR",
            exchangeRateUsed = 0.91, isBusinessExpense = false,
            source = "MANUAL", notes = "Morning coffee", categoryId = 3
        )
        val dateStr = Instant.ofEpochMilli(tx.date).atZone(zoneId).toLocalDate().format(dateFormatter)
        val createdAtStr = Instant.ofEpochMilli(tx.createdAt).atZone(zoneId).toLocalDate().format(dateFormatter)

        val csv = buildString {
            appendLine("# ExpenseTracker Export v2, rowCount=1, startDate=${tx.date}, endDate=${tx.date}")
            appendLine("ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,IsBusinessExpense,BusinessPurpose")
            append("${tx.id},")
            append("$dateStr,")
            append("$createdAtStr,")
            append("${tx.merchant},")
            append("${tx.amount},")
            append("${tx.effectiveAmount},")
            append("${tx.currency},")
            append("${tx.transactionType.name},")
            append("Coffee,")
            append("${tx.notes ?: ""},")
            append("${tx.source ?: ""},")
            append("${tx.paymentMethod},")
            append("${tx.originalCurrency},")
            append("${tx.originalAmount?.toString() ?: ""},")
            append("${tx.homeCurrency},")
            append("${tx.baseAmount},")
            append("${tx.baseCurrency},")
            append("${tx.exchangeRateUsed},")
            append("${if (tx.isBusinessExpense) "true" else "false"},")
            append("${tx.businessPurpose ?: ""}")
            append("\n")
        }

        val result = importer.importFromContent(csv)
        assertThat(result).isInstanceOf(CsvExpenseImporter.ImportResult.Success::class.java)
        val success = result as CsvExpenseImporter.ImportResult.Success
        assertThat(success.imported).isEqualTo(1)
        assertThat(success.errors).isEqualTo(0)

        val request = requestSlot.captured
        assertThat(request.merchant).isEqualTo("Starbucks")
        assertThat(request.amount).isEqualTo(25.50)
        assertThat(request.currency).isEqualTo("EUR")
        assertThat(request.notes).isEqualTo("Morning coffee")
        assertThat(request.categoryId).isEqualTo(3L)
    }

    @Test
    fun csvV2Import_skipsMetadataCommentLine() = runTest {
        val cat = Category(id = 5, name = "Food", icon = "F", color = "#00FF00")
        coEvery { categoryDao.getByName("Food") } returns cat
        coEvery { currencyRepo.homeCurrency() } returns flowOf("EUR")
        coEvery { currencyRepo.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(10L)

        val csv = """
            # ExpenseTracker Export v2, rowCount=2, startDate=1705276800000, endDate=1705363200000
            ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,Category,Notes,Source,PaymentMethod,OriginalCurrency,OriginalAmount,HomeCurrency,BaseAmount,BaseCurrency,ExchangeRateUsed,IsBusinessExpense,BusinessPurpose
            1,2024-01-15,2024-01-15,Pizza Place,10.00,10.00,EUR,PURCHASE,Food,Lunch,MANUAL,CARD,EUR,,EUR,0.0,EUR,0.0,false,
            # This is a mid-file comment that should be skipped
            2,2024-01-16,2024-01-16,Burger Joint,12.50,12.50,EUR,PURCHASE,Food,Dinner,MANUAL,CARD,EUR,,EUR,0.0,EUR,0.0,false,
        """.trimIndent()

        val result = importer.importFromContent(csv)
        assertThat(result).isInstanceOf(CsvExpenseImporter.ImportResult.Success::class.java)
        val success = result as CsvExpenseImporter.ImportResult.Success
        assertThat(success.imported).isEqualTo(2)
        assertThat(success.errors).isEqualTo(0)
    }
}
