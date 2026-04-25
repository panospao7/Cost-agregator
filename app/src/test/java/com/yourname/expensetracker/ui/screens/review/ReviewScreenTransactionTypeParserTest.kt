package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewScreenTransactionTypeParserTest {

    @Test
    fun `parseTransactionTypeOrNull returns enum for valid value`() {
        assertEquals(TransactionType.PURCHASE, parseTransactionTypeOrNull("PURCHASE"))
    }

    @Test
    fun `parseTransactionTypeOrNull returns null for invalid value`() {
        assertNull(parseTransactionTypeOrNull("NOT_A_TYPE"))
    }

    @Test
    fun `parseTransactionTypeOrNull returns null for blank input`() {
        assertNull(parseTransactionTypeOrNull("  "))
        assertNull(parseTransactionTypeOrNull(null))
    }
}
