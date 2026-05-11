package com.yourname.expensetracker.util

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CsvExpenseImporterTest {

    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val coordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)

    private lateinit var importer: CsvExpenseImporter

    @Before
    fun setup() {
        importer = CsvExpenseImporter(categoryDao, coordinator, currencySettingsRepository = mockk())
    }

    @Test
    fun `importer can be instantiated with injected DAOs`() {
        val imp = CsvExpenseImporter(categoryDao, mockk<TransactionLifecycleCoordinator>(relaxed = true), currencySettingsRepository = mockk())
        assertThat(imp).isNotNull()
    }

    @Test
    fun `imports single valid line`() = runTest {
        val cat = Category(id = 5, name = "Coffee", icon = "C", color = "#FF0000")
        coEvery { categoryDao.getByName("Coffee") } returns cat
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-01-15,25.50,Starbucks,Coffee,Morning latte"
        val result = importer.importFromContent(csv)

        assertThat(result).isInstanceOf(CsvExpenseImporter.ImportResult.Success::class.java)
        val success = result as CsvExpenseImporter.ImportResult.Success
        assertThat(success.imported).isEqualTo(1)
        assertThat(success.errors).isEqualTo(0)
    }

    @Test
    fun `skips header line`() = runTest {
        val cat = Category(id = 1, name = "Food", icon = "F", color = "#00FF00")
        coEvery { categoryDao.getByName(any()) } returns cat
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val csv = """
            date,amount,merchant,category,description
            2024-01-15,10.00,Pizza Place,Food,Lunch
        """.trimIndent()

        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success
        assertThat(result.imported).isEqualTo(1)
        coVerify(exactly = 1) { coordinator.createExpense(any()) }
    }

    @Test
    fun `creates category when not found`() = runTest {
        coEvery { categoryDao.getByName("NewCat") } returns null
        coEvery { categoryDao.insert(any()) } returns 99L
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-02-01,5.00,Shop,NewCat,Stuff"
        importer.importFromContent(csv)

        coVerify { categoryDao.insert(any()) }
    }

    @Test
    fun `uses existing category when found`() = runTest {
        val existing = Category(id = 42, name = "Transport", icon = "T", color = "#00FF00")
        coEvery { categoryDao.getByName("Transport") } returns existing
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-03-01,15.00,Uber,Transport,Ride"
        importer.importFromContent(csv)

        assertThat(requestSlot.captured.categoryId).isEqualTo(42L)
    }

    @Test
    fun `empty content returns success with zero counts`() = runTest {
        val result = importer.importFromContent("")
        assertThat(result).isInstanceOf(CsvExpenseImporter.ImportResult.Success::class.java)
        val success = result as CsvExpenseImporter.ImportResult.Success
        assertThat(success.imported).isEqualTo(0)
        assertThat(success.errors).isEqualTo(0)
    }

    @Test
    fun `strips currency symbols from amount`() = runTest {
        val cat = Category(id = 1, name = "Food", icon = "F", color = "#AABBCC")
        coEvery { categoryDao.getByName(any()) } returns cat
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-01-01,\u20AC12.50,Cafe,Food,test"
        importer.importFromContent(csv)

        assertThat(requestSlot.captured.amount).isEqualTo(12.50)
    }

    @Test
    fun `reports progress callback`() = runTest {
        val cat = Category(id = 1, name = "Cat", icon = "C", color = "#112233")
        coEvery { categoryDao.getByName(any()) } returns cat
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val csv = "date,amount,merchant,category,description\n2024-01-01,1.00,A,Cat,x\n2024-01-02,2.00,B,Cat,y"
        importer.importFromContent(csv) { current, total ->
            progressCalls.add(current to total)
        }

        assertThat(progressCalls).isNotEmpty()
        assertThat(progressCalls.last().first).isEqualTo(progressCalls.last().second)
    }

    @Test
    fun `counts errors without aborting`() = runTest {
        val cat = Category(id = 1, name = "Cat", icon = "C", color = "#112233")
        coEvery { categoryDao.getByName(any()) } returns cat
        coEvery { coordinator.createExpense(any()) } throws RuntimeException("DB error") andThen CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-01-01,1.00,A,Cat,x\n2024-01-02,2.00,B,Cat,y"
        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.errors).isEqualTo(1)
        assertThat(result.imported).isEqualTo(1)
    }

    @Test
    fun `invalid csv date increments error count and does not import row`() = runTest {
        val cat = Category(id = 1, name = "Cat", icon = "C", color = "#112233")
        coEvery { categoryDao.getByName(any()) } returns cat
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-13-40,1.00,A,Cat,bad\n2024-01-02,2.00,B,Cat,ok"
        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.errors).isEqualTo(1)
        assertThat(result.imported).isEqualTo(1)
        coVerify(exactly = 1) { coordinator.createExpense(any()) }
    }

    @Test
    fun `imports quoted fields with embedded commas`() = runTest {
        val cat = Category(id = 9, name = "Food", icon = "F", color = "#00FF00")
        coEvery { categoryDao.getByName("Food") } returns cat
        val requestSlot = slot<CreateExpenseRequest>()
        coEvery { coordinator.createExpense(capture(requestSlot)) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-01-15,25.50,\"Starbucks, Downtown\",Food,\"Morning, coffee\""
        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.errors).isEqualTo(0)
        assertThat(requestSlot.captured.merchant).isEqualTo("Starbucks, Downtown")
        assertThat(requestSlot.captured.notes).isEqualTo("Morning, coffee")
    }

    @Test
    fun `invalid quoted csv row increments error count and skips insert`() = runTest {
        val cat = Category(id = 1, name = "Cat", icon = "C", color = "#112233")
        coEvery { categoryDao.getByName(any()) } returns cat
        coEvery { coordinator.createExpense(any()) } returns CreateExpenseResult.Created(1L)

        val csv = "date,amount,merchant,category,description\n2024-01-15,25.50,\"Unclosed merchant,Food,Desc"
        val result = importer.importFromContent(csv) as CsvExpenseImporter.ImportResult.Success

        assertThat(result.imported).isEqualTo(0)
        assertThat(result.errors).isEqualTo(1)
        coVerify(exactly = 0) { coordinator.createExpense(any()) }
    }
}