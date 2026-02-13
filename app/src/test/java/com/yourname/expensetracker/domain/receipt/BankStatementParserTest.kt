package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BankStatementParserTest {
    private lateinit var parser: BankStatementParser
    private lateinit var currencyNormalizer: com.yourname.expensetracker.domain.util.CurrencyNormalizer
    private lateinit var merchantCleaner: com.yourname.expensetracker.domain.util.MerchantCleaner

    @Before
    fun setup() {
        currencyNormalizer = io.mockk.mockk {
            io.mockk.every { normalize(any()) } returns "EUR"
        }
        merchantCleaner = io.mockk.mockk {
            io.mockk.every { clean(any()) } answers { firstArg() }
        }
        parser = BankStatementParser(currencyNormalizer, merchantCleaner)
    }

    @Test
    fun `parse multiple transactions from spatial blocks`() {
        // Mock a bank statement screenshot logic
        // Row 1: 10/05 SKLAVENITIS -12,50 EUR
        val blocks = listOf(
            TextBlock("10/05", null, 10, 100, 50, 120),
            TextBlock("SKLAVENITIS", null, 60, 100, 200, 120),
            TextBlock("-12,50", null, 250, 100, 300, 120),
            TextBlock("EUR", null, 310, 100, 350, 120),
            
            // Row 2: 11/05 LIDL -25,00 EUR
            TextBlock("11/05", null, 10, 150, 50, 170),
            TextBlock("LIDL", null, 60, 150, 200, 170),
            TextBlock("-25,00", null, 250, 150, 300, 170),
            TextBlock("EUR", null, 310, 150, 350, 170),
            
            // Row 3: 12/05 SALARY +1500,00 EUR
            TextBlock("12/05", null, 10, 200, 50, 220),
            TextBlock("SALARY", null, 60, 200, 200, 220),
            TextBlock("1500,00", null, 250, 200, 300, 220), // Note: + often missed by OCR or represented by absence of -
            TextBlock("EUR", null, 310, 200, 350, 220)
        )

        val results = parser.parse(blocks)

        assertEquals(3, results.size)
        
        // Check first transaction
        assertEquals(12.50, results[0].amount, 0.01)
        assertEquals("SKLAVENITIS", results[0].merchant)
        assertEquals(TransactionType.PURCHASE, results[0].type)
        
        // Check second transaction
        assertEquals(25.0, results[1].amount, 0.01)
        assertEquals("LIDL", results[1].merchant)
        assertEquals(TransactionType.PURCHASE, results[1].type)
        
        // Check third transaction
        assertEquals(1500.0, results[2].amount, 0.01)
        assertEquals("SALARY", results[2].merchant)
        assertEquals(TransactionType.DEPOSIT, results[2].type)
    }

    @Test
    fun `group blocks into rows correctly even with slight vertical variation`() {
        val blocks = listOf(
            TextBlock("Row1-Left", null, 10, 100, 50, 120),
            TextBlock("Row1-Right", null, 100, 105, 150, 125), // 5px offset
            
            TextBlock("Row2-Left", null, 10, 200, 50, 220),
            TextBlock("Row2-Right", null, 100, 195, 150, 215) // -5px offset
        )

        // Internal method test via parse call and checking result size (simplified)
        // Since groupBlocksIntoRows is private, we check the effect. 
        // We'll need a helper or just trust the logic if it passes the main test.
        // Actually I'll just check if it extracts 2 transactions if I give it proper amounts.
        
        val blocksWithAmounts = listOf(
            TextBlock("Merchant1", null, 10, 100, 50, 120),
            TextBlock("-10,00 EUR", null, 100, 105, 150, 125),
            
            TextBlock("Merchant2", null, 10, 200, 50, 220),
            TextBlock("-20,00 EUR", null, 100, 195, 150, 215)
        )
        
        val results = parser.parse(blocksWithAmounts)
        assertEquals(2, results.size)
        assertEquals("Merchant1", results[0].merchant)
        assertEquals("Merchant2", results[1].merchant)
    }
}
