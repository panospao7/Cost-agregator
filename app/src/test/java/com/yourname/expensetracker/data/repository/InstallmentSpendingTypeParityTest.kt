package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5: the plan feature has exactly one definition of "a spending type"
 * ([InstallmentExpenseFacts.isSpendingType]) and it must stay equal to the canonical SQL / query
 * spending type ([ExpenseDao.SPENDING_TYPE]). Expected values are the literal canonical name, not a
 * recomputation of the production predicate.
 */
class InstallmentSpendingTypeParityTest {

    @Test
    fun `F5 the installment spending type set equals the canonical ExpenseDao spending type`() {
        val installmentSpendingTypes = TransactionType.entries
            .filter { InstallmentExpenseFacts.isSpendingType(it) }
            .map { it.name }

        assertEquals(listOf(ExpenseDao.SPENDING_TYPE), installmentSpendingTypes)
    }

    @Test
    fun `F5 the canonical spending type is the purchase type and the SQL fragments use it`() {
        assertEquals("PURCHASE", ExpenseDao.SPENDING_TYPE)
        assertTrue(ExpenseDao.SPENDING_TYPE_SQL.contains("'${ExpenseDao.SPENDING_TYPE}'"))
        assertTrue(ExpenseDao.SPENDING_TYPE_E_SQL.contains("'${ExpenseDao.SPENDING_TYPE}'"))
    }

    @Test
    fun `F5 every non purchase transaction type is not a spending type`() {
        for (type in TransactionType.entries) {
            if (type.name == ExpenseDao.SPENDING_TYPE) continue
            assertTrue("type $type must not be a spending type", !InstallmentExpenseFacts.isSpendingType(type))
        }
    }
}
