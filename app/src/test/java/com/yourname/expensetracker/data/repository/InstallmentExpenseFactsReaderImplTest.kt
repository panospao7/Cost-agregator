package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.SpendingExclusionFlagRow
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentFlaggedExpense
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [InstallmentExpenseFactsReaderImpl]: money and flag mapping (findExpenseMoney,
 * findExpenseMoneyBetween, findFlaggedExpenses) and the facts mapping (hasSplit, spending type,
 * linked occurrence). The reader only reads; the DAOs are strict mocks, so any unstubbed call,
 * and in particular any write, fails the test. Synthetic data only.
 */
class InstallmentExpenseFactsReaderImplTest {

    private lateinit var expenseDao: ExpenseDao
    private lateinit var occurrenceDao: RecurringOccurrenceDao
    private lateinit var categoryDao: CategoryDao
    private lateinit var reader: InstallmentExpenseFactsReaderImpl

    @Before
    fun setUp() {
        expenseDao = mockk()
        occurrenceDao = mockk()
        categoryDao = mockk()
        reader = InstallmentExpenseFactsReaderImpl(expenseDao, occurrenceDao, categoryDao)
    }

    private fun expense(
        id: Long = 900L,
        amount: Double = 50.0,
        currency: String = "EUR",
        type: TransactionType = TransactionType.PURCHASE,
        date: Long = 1_800_000_000_000L,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        mySharePercentage: Int? = null,
        splitTemplateId: Long? = null,
        splitVisualization: String? = null,
        reason: String? = null,
        categoryId: Long? = null,
        paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN
    ) = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "Synthetic Shop",
        transactionType = type,
        date = date,
        categoryId = categoryId,
        paymentMethod = paymentMethod,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        myShareAmount = myShareAmount,
        mySharePercentage = mySharePercentage,
        splitTemplateId = splitTemplateId,
        splitVisualization = splitVisualization,
        spendingExclusionReason = reason
    )

    private fun occurrence(id: Long) = RecurringOccurrence(
        id = id,
        sourceType = "RECURRING_RULE",
        sourceId = 1L,
        occurrenceKey = "synthetic-key-$id",
        dueDate = 0L,
        status = "PLANNED",
        expectedAmount = 1.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = null
    )

    private fun givenExpense(e: Expense?, linkedOccurrence: RecurringOccurrence? = null) {
        coEvery { expenseDao.getById(900L) } returns e
        coEvery { occurrenceDao.getByLinkedExpenseId(900L) } returns linkedOccurrence
    }

    // ------------------------------------------------------------------ money

    @Test
    fun `findExpenseMoney maps the stored gross and effective amount currency and date`() = runTest {
        givenExpense(expense(amount = 53.0, currency = "USD", date = 1_811_111_111_000L))

        val money = reader.findExpenseMoney(900L)!!

        assertEquals(900L, money.expenseId)
        assertEquals("USD", money.currency)
        assertEquals(1_811_111_111_000L, money.dateMillis)
        assertEquals(53.0, money.amount, 0.0)
        assertEquals(53.0, money.effectiveAmount, 0.0)
    }

    @Test
    fun `findExpenseMoney keeps gross but reports effective zero for a not mine expense`() = runTest {
        givenExpense(expense(amount = 50.0, isNotMine = true))

        val money = reader.findExpenseMoney(900L)!!

        assertEquals(50.0, money.amount, 0.0)
        assertEquals(0.0, money.effectiveAmount, 0.0)
    }

    @Test
    fun `findExpenseMoney reports effective zero for a flagged expense and keeps gross`() = runTest {
        givenExpense(expense(amount = 600.0, reason = "INSTALLMENT_PURCHASE"))

        val money = reader.findExpenseMoney(900L)!!

        assertEquals(600.0, money.amount, 0.0)
        assertEquals(0.0, money.effectiveAmount, 0.0)
    }

    @Test
    fun `findExpenseMoney uses the explicit or proportional share for a shared expense`() = runTest {
        givenExpense(expense(amount = 80.0, isShared = true, myShareAmount = 20.0))
        assertEquals(20.0, reader.findExpenseMoney(900L)!!.effectiveAmount, 0.0)

        givenExpense(expense(amount = 80.0, isShared = true, mySharePercentage = 25))
        assertEquals(20.0, reader.findExpenseMoney(900L)!!.effectiveAmount, 0.0)
        assertEquals(80.0, reader.findExpenseMoney(900L)!!.amount, 0.0)
    }

    @Test
    fun `findExpenseMoney is null when the expense does not exist and not a zero amount`() = runTest {
        givenExpense(null)

        assertNull(reader.findExpenseMoney(900L))
    }

    @Test
    fun `findExpenseMoneyBetween passes the bounds through and maps every row`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(1_000L, 2_000L) } returns listOf(
            expense(id = 1L, amount = 10.0, date = 1_500L),
            expense(id = 2L, amount = 20.0, currency = "USD", date = 1_600L, isNotMine = false)
        )

        val result = reader.findExpenseMoneyBetween(1_000L, 2_000L)

        assertEquals(listOf(1L, 2L), result.map { it.expenseId })
        assertEquals(listOf(10.0, 20.0), result.map { it.amount })
        assertEquals(listOf("EUR", "USD"), result.map { it.currency })
        assertEquals(listOf(1_500L, 1_600L), result.map { it.dateMillis })
        coVerify(exactly = 1) { expenseDao.getExpensesBetweenUncapped(1_000L, 2_000L) }
    }

    @Test
    fun `findExpenseMoneyBetween returns an empty list for an empty range`() = runTest {
        coEvery { expenseDao.getExpensesBetweenUncapped(any(), any()) } returns emptyList()

        assertTrue(reader.findExpenseMoneyBetween(5L, 5L).isEmpty())
    }

    // ------------------------------------------------------------------ flags

    @Test
    fun `findFlaggedExpenses maps id and flag name only`() = runTest {
        coEvery { expenseDao.getSpendingExclusionFlagRows() } returns listOf(
            SpendingExclusionFlagRow(1L, "INSTALLMENT_PURCHASE"),
            SpendingExclusionFlagRow(2L, "INSTALLMENT_REVERSED")
        )

        assertEquals(
            listOf(
                InstallmentFlaggedExpense(1L, "INSTALLMENT_PURCHASE"),
                InstallmentFlaggedExpense(2L, "INSTALLMENT_REVERSED")
            ),
            reader.findFlaggedExpenses()
        )
    }

    @Test
    fun `findFlaggedExpenses is empty when nothing is flagged`() = runTest {
        coEvery { expenseDao.getSpendingExclusionFlagRows() } returns emptyList()

        assertTrue(reader.findFlaggedExpenses().isEmpty())
    }

    // ------------------------------------------------------------------ facts

    @Test
    fun `findExpenseFacts maps a plain purchase`() = runTest {
        givenExpense(expense(categoryId = 7L, paymentMethod = PaymentMethod.CARD, date = 1_800_000_000_123L))

        val facts = reader.findExpenseFacts(900L)!!

        assertEquals(900L, facts.expenseId)
        assertEquals("EUR", facts.currency)
        assertEquals(1_800_000_000_123L, facts.dateMillis)
        assertTrue(facts.isSpendingType)
        assertFalse(facts.isNotMine)
        assertFalse(facts.isSharedExpense)
        assertFalse(facts.hasSplit)
        assertNull(facts.spendingExclusionReason)
        assertEquals(7L, facts.categoryId)
        assertEquals(PaymentMethod.CARD, facts.paymentMethod)
        assertNull(facts.linkedRecurringOccurrenceId)
    }

    @Test
    fun `findExpenseFacts passes the ownership and exclusion flags through`() = runTest {
        givenExpense(expense(isNotMine = true, reason = "INSTALLMENT_REVERSED"))
        val notMine = reader.findExpenseFacts(900L)!!
        assertTrue(notMine.isNotMine)
        assertEquals("INSTALLMENT_REVERSED", notMine.spendingExclusionReason)

        givenExpense(expense(isShared = true))
        assertTrue(reader.findExpenseFacts(900L)!!.isSharedExpense)
    }

    @Test
    fun `findExpenseFacts hasSplit is true for a split template`() = runTest {
        givenExpense(expense(splitTemplateId = 3L))

        assertTrue(reader.findExpenseFacts(900L)!!.hasSplit)
    }

    @Test
    fun `findExpenseFacts hasSplit is true for a non blank split visualization`() = runTest {
        givenExpense(expense(splitVisualization = "{\"segments\":[]}"))

        assertTrue(reader.findExpenseFacts(900L)!!.hasSplit)
    }

    @Test
    fun `findExpenseFacts hasSplit is false for a blank or null split visualization without a template`() = runTest {
        for (visualization in listOf(null, "", "   ")) {
            givenExpense(expense(splitVisualization = visualization))

            assertFalse("visualization '$visualization'", reader.findExpenseFacts(900L)!!.hasSplit)
        }
    }

    @Test
    fun `findExpenseFacts spending type is true only for a purchase`() = runTest {
        for (type in TransactionType.entries) {
            givenExpense(expense(type = type))

            assertEquals(
                "type $type",
                type == TransactionType.PURCHASE,
                reader.findExpenseFacts(900L)!!.isSpendingType
            )
        }
    }

    @Test
    fun `findExpenseFacts reports the linked recurring occurrence id`() = runTest {
        givenExpense(expense(), linkedOccurrence = occurrence(5L))

        assertEquals(5L, reader.findExpenseFacts(900L)!!.linkedRecurringOccurrenceId)
    }

    @Test
    fun `findExpenseFacts is null for a missing expense and does not look for an occurrence`() = runTest {
        givenExpense(null)

        assertNull(reader.findExpenseFacts(900L))
        coVerify(exactly = 0) { occurrenceDao.getByLinkedExpenseId(any()) }
    }

    // ------------------------------------------------------------------ category

    @Test
    fun `categoryExists is true only when the category row exists`() = runTest {
        coEvery { categoryDao.getById(7L) } returns Category(id = 7L, name = "Synthetic", icon = "x", color = "#000000")
        coEvery { categoryDao.getById(8L) } returns null

        assertTrue(reader.categoryExists(7L))
        assertFalse(reader.categoryExists(8L))
    }
}
