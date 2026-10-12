package com.yourname.expensetracker.domain.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceCurrencyAllocationTest {
    @Test
    fun `one cent is allocated once with a stable remainder tie break`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.01, CurrencyCode.EUR), listOf(1.0, 1.0, 1.0))
        assertThat(result.shares.map { it.amount }).containsExactly(0.01, 0.0, 0.0).inOrder()
    }

    @Test
    fun `source precision rounds requested tax with half even`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.005, CurrencyCode.EUR), listOf(1.0))
        assertThat(result.requested.amount).isEqualTo(0.0)
        assertThat(result.shares.single().amount).isEqualTo(0.0)
    }

    @Test
    fun `yen uses whole currency units`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(1.0, CurrencyCode.JPY), listOf(1.0, 1.0))
        assertThat(result.shares.map { it.amount }).containsExactly(1.0, 0.0).inOrder()
    }

    @Test
    fun `signed discount adjustments preserve proportional tax and conservation`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(8.0, CurrencyCode.EUR), listOf(100.0, -20.0))
        assertThat(result.shares.map { it.amount }).containsExactly(10.0, -2.0).inOrder()
    }

    @Test(expected = MoneyDisplayUnavailableException::class)
    fun `nonpositive item basis cannot allocate a positive tax`() {
        SourceCurrencyAllocation.allocate(MoneyAmount(1.0, CurrencyCode.EUR), listOf(0.0))
    }

    @Test
    fun `rounding never assigns tax to a zero weight item`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.01, CurrencyCode.EUR), listOf(0.0, 1.0, 1.0))
        assertThat(result.shares.map { it.amount }).containsExactly(0.0, 0.01, 0.0).inOrder()
    }

    @Test
    fun `largest fractional remainder receives the remaining minor unit`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.01, CurrencyCode.EUR), listOf(3.0, 7.0))
        assertThat(result.shares.map { it.amount }).containsExactly(0.0, 0.01).inOrder()
    }

    @Test
    fun `signed fractional shares conserve the request with stable ties`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.01, CurrencyCode.EUR), listOf(3.0, -1.0))
        assertThat(result.shares.map { it.amount }).containsExactly(0.02, -0.01).inOrder()
    }

    @Test
    fun `extreme weight scales do not assign a minor unit to zero weights`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.01, CurrencyCode.EUR), listOf(0.0, 1e-100, 1.0))
        assertThat(result.shares.map { it.amount }).containsExactly(0.0, 0.0, 0.01).inOrder()
    }

    @Test
    fun `valid zero request with an empty basis is available`() {
        val result = SourceCurrencyAllocation.allocate(MoneyAmount(0.0, CurrencyCode.EUR), emptyList())
        assertThat(result.requested.amount).isEqualTo(0.0)
        assertThat(result.shares).isEmpty()
    }

    @Test(expected = MoneyDisplayUnavailableException::class)
    fun `unsupported ISO currency fails closed`() {
        SourceCurrencyAllocation.allocate(MoneyAmount(1.0, CurrencyCode("XXX")), listOf(1.0))
    }

}
