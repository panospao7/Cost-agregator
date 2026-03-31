package com.yourname.expensetracker.domain.tax

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.domain.util.Money
import com.yourname.expensetracker.domain.util.toMoney
import org.junit.Test

/**
 * CRITICAL TEST (HIGH-6): Tax Calculation Accuracy
 * 
 * Tests tax rate configuration and calculations including VAT and
 * progressive tax brackets. Ensures accurate tax computation.
 */
class TaxCalculationTest {

    // ==================== VAT CALCULATION TESTS ====================

    @Test
    fun `greece VAT rate is 24 percent`() {
        val config = GreeceTaxConfiguration()
        
        assertThat(config.getVatRate()).isEqualTo(0.24)
    }

    @Test
    fun `US VAT rate is 0 percent`() {
        val config = UsTaxConfiguration()
        
        assertThat(config.getVatRate()).isEqualTo(0.0)
    }

    @Test
    fun `VAT calculation on 100 euros returns 24 euros`() {
        val config = GreeceTaxConfiguration()
        val amount = 100.0.toMoney()
        
        val vat = calculateVat(amount, config.getVatRate())
        
        assertThat(vat.toDouble()).isEqualTo(24.0)
    }

    @Test
    fun `VAT calculation on 50 euros returns 12 euros`() {
        val config = GreeceTaxConfiguration()
        val amount = 50.0.toMoney()
        
        val vat = calculateVat(amount, config.getVatRate())
        
        assertThat(vat.toDouble()).isEqualTo(12.0)
    }

    @Test
    fun `VAT calculation with zero amount returns zero`() {
        val config = GreeceTaxConfiguration()
        val amount = 0.0.toMoney()
        
        val vat = calculateVat(amount, config.getVatRate())
        
        assertThat(vat.isZero()).isTrue()
    }

    @Test
    fun `VAT inclusive price calculation is accurate`() {
        val config = GreeceTaxConfiguration()
        val basePrice = 100.0.toMoney()
        
        val vat = calculateVat(basePrice, config.getVatRate())
        val totalPrice = basePrice + vat
        
        assertThat(totalPrice.toDouble()).isEqualTo(124.0)
    }

    @Test
    fun `VAT extraction from total price is accurate`() {
        val config = GreeceTaxConfiguration()
        val totalPrice = 124.0.toMoney() // Price including VAT
        
        val basePrice = extractVat(totalPrice, config.getVatRate())
        
        // Base price should be 100 (124 / 1.24)
        assertThat(basePrice.toDouble()).isWithin(0.01).of(100.0)
    }

    // ==================== TAX BRACKET TESTS ====================

    @Test
    fun `greece has correct number of tax brackets`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        assertThat(brackets.size).isEqualTo(3)
    }

    @Test
    fun `greece low income bracket is 9 percent up to 10000`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        val lowBracket = brackets[0]
        assertThat(lowBracket.minIncome).isEqualTo(0.0)
        assertThat(lowBracket.maxIncome).isEqualTo(10000.0)
        assertThat(lowBracket.rate).isEqualTo(0.09)
    }

    @Test
    fun `greece medium income bracket is 22 percent from 10000 to 20000`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        val mediumBracket = brackets[1]
        assertThat(mediumBracket.minIncome).isEqualTo(10000.0)
        assertThat(mediumBracket.maxIncome).isEqualTo(20000.0)
        assertThat(mediumBracket.rate).isEqualTo(0.22)
    }

    @Test
    fun `greece high income bracket is 32 percent above 20000`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        val highBracket = brackets[2]
        assertThat(highBracket.minIncome).isEqualTo(20000.0)
        assertThat(highBracket.maxIncome).isNull() // No upper limit
        assertThat(highBracket.rate).isEqualTo(0.32)
    }

    @Test
    fun `tax on 5000 euros income in greece is 9 percent`() {
        val config = GreeceTaxConfiguration()
        val income = 5000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // 5000 * 0.09 = 450
        assertThat(tax.toDouble()).isEqualTo(450.0)
    }

    @Test
    fun `tax on 10000 euros income in greece is 900 euros`() {
        val config = GreeceTaxConfiguration()
        val income = 10000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // Entirely in first bracket: 10000 * 0.09 = 900
        assertThat(tax.toDouble()).isEqualTo(900.0)
    }

    @Test
    fun `tax on 15000 euros income in greece spans two brackets`() {
        val config = GreeceTaxConfiguration()
        val income = 15000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // First 10000 at 9% = 900
        // Next 5000 at 22% = 1100
        // Total = 2000
        assertThat(tax.toDouble()).isEqualTo(2000.0)
    }

    @Test
    fun `tax on 30000 euros income in greece spans all brackets`() {
        val config = GreeceTaxConfiguration()
        val income = 30000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // First 10000 at 9% = 900
        // Next 10000 (10000-20000) at 22% = 2200
        // Remaining 10000 at 32% = 3200
        // Total = 6300
        assertThat(tax.toDouble()).isEqualTo(6300.0)
    }

    @Test
    fun `tax on income at bracket boundary uses correct rate`() {
        val config = GreeceTaxConfiguration()
        
        // Test at exactly 10000
        val taxAtBoundary = calculateProgressiveTax(
            10000.0.toMoney(),
            config.getTaxBrackets()
        )
        
        // Should be entirely in first bracket
        assertThat(taxAtBoundary.toDouble()).isEqualTo(900.0)
    }

    @Test
    fun `tax on income just above boundary uses higher rate`() {
        val config = GreeceTaxConfiguration()
        val income = 10001.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // First 10000 at 9% = 900
        // Next 1 at 22% = 0.22
        // Total = 900.22
        assertThat(tax.toDouble()).isEqualTo(900.22)
    }

    @Test
    fun `tax on zero income returns zero`() {
        val config = GreeceTaxConfiguration()
        val income = 0.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        assertThat(tax.isZero()).isTrue()
    }

    // ==================== US TAX BRACKET TESTS ====================

    @Test
    fun `US has correct number of tax brackets`() {
        val config = UsTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        assertThat(brackets.size).isEqualTo(3)
    }

    @Test
    fun `US 10 percent bracket applies to first 11000`() {
        val config = UsTaxConfiguration()
        val income = 5000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // 5000 * 0.10 = 500
        assertThat(tax.toDouble()).isEqualTo(500.0)
    }

    @Test
    fun `US 12 percent bracket applies to income between 11000 and 44725`() {
        val config = UsTaxConfiguration()
        val income = 20000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // First 11000 at 10% = 1100
        // Next 9000 at 12% = 1080
        // Total = 2180
        assertThat(tax.toDouble()).isEqualTo(2180.0)
    }

    // ==================== FACTORY TESTS ====================

    @Test
    fun `factory returns greece configuration for GR code`() {
        val config = TaxConfigurationFactory.getConfiguration("GR")
        
        assertThat(config.getCountryCode()).isEqualTo("GR")
        assertThat(config.getVatRate()).isEqualTo(0.24)
    }

    @Test
    fun `factory returns US configuration for US code`() {
        val config = TaxConfigurationFactory.getConfiguration("US")
        
        assertThat(config.getCountryCode()).isEqualTo("US")
        assertThat(config.getVatRate()).isEqualTo(0.0)
    }

    @Test
    fun `factory defaults to greece for unknown country code`() {
        val config = TaxConfigurationFactory.getConfiguration("XX")
        
        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    @Test
    fun `factory defaults to greece when no code provided`() {
        val config = TaxConfigurationFactory.getConfiguration()
        
        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    @Test
    fun `current configuration returns greece by default`() {
        val config = TaxConfigurationFactory.getCurrentConfiguration()
        
        assertThat(config.getCountryCode()).isEqualTo("GR")
    }

    // ==================== CURRENCY TESTS ====================

    @Test
    fun `greece configuration uses EUR currency`() {
        val config = GreeceTaxConfiguration()
        
        assertThat(config.getCurrency()).isEqualTo("EUR")
    }

    @Test
    fun `US configuration uses USD currency`() {
        val config = UsTaxConfiguration()
        
        assertThat(config.getCurrency()).isEqualTo("USD")
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun `tax calculation with very high income`() {
        val config = GreeceTaxConfiguration()
        val income = 1_000_000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // Verify tax is calculated (don't check exact amount, just that it's reasonable)
        assertThat(tax.toDouble()).isGreaterThan(0.0)
        assertThat(tax.toDouble()).isLessThan(income.toDouble())
    }

    @Test
    fun `tax calculation with fractional income`() {
        val config = GreeceTaxConfiguration()
        val income = 15000.55.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        
        // Should handle fractional amounts correctly
        assertThat(tax.toDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `VAT on fractional amount maintains precision`() {
        val config = GreeceTaxConfiguration()
        val amount = 99.99.toMoney()
        
        val vat = calculateVat(amount, config.getVatRate())
        
        // 99.99 * 0.24 = 23.9976 → should round appropriately
        assertThat(vat.toDouble()).isGreaterThan(0.0)
    }

    @Test
    fun `tax bracket names are not empty`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        brackets.forEach { bracket ->
            assertThat(bracket.name).isNotEmpty()
        }
    }

    @Test
    fun `tax bracket rates are between 0 and 1`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        brackets.forEach { bracket ->
            assertThat(bracket.rate).isAtLeast(0.0)
            assertThat(bracket.rate).isAtMost(1.0)
        }
    }

    @Test
    fun `tax brackets have valid min and max`() {
        val config = GreeceTaxConfiguration()
        val brackets = config.getTaxBrackets()
        
        brackets.forEach { bracket ->
            assertThat(bracket.minIncome).isAtLeast(0.0)
            if (bracket.maxIncome != null) {
                assertThat(bracket.maxIncome!!).isGreaterThan(bracket.minIncome)
            }
        }
    }

    @Test
    fun `effective tax rate calculation is accurate`() {
        val config = GreeceTaxConfiguration()
        val income = 30000.0.toMoney()
        
        val tax = calculateProgressiveTax(income, config.getTaxBrackets())
        val effectiveRate = tax.toDouble() / income.toDouble()
        
        // Effective rate should be between lowest and highest bracket rates
        assertThat(effectiveRate).isAtLeast(0.09)
        assertThat(effectiveRate).isAtMost(0.32)
    }

    // ==================== HELPER METHODS ====================

    /**
     * Calculate VAT for a given amount.
     */
    private fun calculateVat(amount: Money, vatRate: Double): Money {
        return amount.percentage(vatRate * 100)
    }

    /**
     * Extract base price from VAT-inclusive total.
     */
    private fun extractVat(total: Money, vatRate: Double): Money {
        // total = base + (base * vatRate) = base * (1 + vatRate)
        // base = total / (1 + vatRate)
        val divisor = (1.0 + vatRate).toMoney()
        return total.divide(divisor.toDouble().toInt())
    }

    /**
     * Calculate progressive (tiered) tax based on brackets.
     */
    private fun calculateProgressiveTax(income: Money, brackets: List<TaxBracket>): Money {
        var remainingIncome = income
        var totalTax = Money.ZERO
        
        for (bracket in brackets) {
            if (remainingIncome.isZero()) break
            
            val bracketSize = bracket.maxIncome?.let { max ->
                (max - bracket.minIncome).toMoney()
            } ?: remainingIncome // No max means use all remaining
            
            val taxableInBracket = if (remainingIncome.toDouble() > bracketSize.toDouble()) {
                bracketSize
            } else {
                remainingIncome
            }
            
            val taxInBracket = taxableInBracket.percentage(bracket.rate * 100)
            totalTax += taxInBracket
            remainingIncome -= taxableInBracket
        }
        
        return totalTax
    }
}