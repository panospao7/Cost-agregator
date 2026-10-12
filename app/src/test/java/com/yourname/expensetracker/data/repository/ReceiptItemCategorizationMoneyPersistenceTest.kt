package com.yourname.expensetracker.data.repository

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao
import com.yourname.expensetracker.data.database.entity.ReceiptItemCategorization
import com.yourname.expensetracker.domain.ai.model.CategorizedReceiptItem
import com.yourname.expensetracker.domain.ai.model.CategorySuggestion
import com.yourname.expensetracker.domain.ai.model.ReceiptItemCategorizationResult
import com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocation
import com.yourname.expensetracker.domain.ai.model.ReceiptTaxAllocator
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiptItemCategorizationMoneyPersistenceTest {
    private val dao = mockk<ReceiptItemCategorizationDao>()
    private val repository = ReceiptItemCategorizationRepository(mockk<DatabaseWriteBarrier>(relaxed = true), dao)
    private val items = listOf(item(3.0), item(7.0))
    private val allocation = ReceiptTaxAllocator.allocate(1.0, items, "EUR") as ReceiptTaxAllocation.Available
    private val result = ReceiptItemCategorizationResult(
        items, 0.9f, false, emptyList(), allocation.categoryAmounts.mapValues { it.value.amount }, allocation
    )

    @Test
    fun `same category items persist individual shares instead of duplicated category tax`() = runTest {
        val saved = mutableListOf<ReceiptItemCategorization>()
        coEvery { dao.insert(capture(saved)) } returns 1L

        assertThat(repository.saveCategorizationResult(1L, result, 1_000L)).isEqualTo(2)

        assertThat(saved.map { it.taxAmount }).containsExactly(0.3, 0.7).inOrder()
        assertThat(saved.map { it.suggestedCategoryId }).containsExactly(1L, 1L)
    }

    @Test
    fun `item category and unallocated projections conserve requested tax`() {
        val mixedItems = listOf(item(40.0), item(60.0).copy(suggestedCategory = null))
        val mixedAllocation = ReceiptTaxAllocator.allocate(10.0, mixedItems, "EUR")
            as ReceiptTaxAllocation.Available
        val requested = java.math.BigDecimal.valueOf(mixedAllocation.requestedTax.amount)
        val itemTotal = mixedAllocation.itemAmounts.fold(java.math.BigDecimal.ZERO) { total, share ->
            total.add(java.math.BigDecimal.valueOf(share.amount))
        }
        val projected = mixedAllocation.categoryAmounts.values.fold(java.math.BigDecimal.ZERO) { total, share ->
            total.add(java.math.BigDecimal.valueOf(share.amount))
        }.add(java.math.BigDecimal.valueOf(mixedAllocation.unallocatedTax.amount))

        assertThat(itemTotal.compareTo(requested)).isEqualTo(0)
        assertThat(projected.compareTo(requested)).isEqualTo(0)
        assertThat(mixedAllocation.categoryAmounts.getValue(1L).amount).isEqualTo(4.0)
        assertThat(mixedAllocation.unallocatedTax.amount).isEqualTo(6.0)
    }

    @Test
    fun `category only legacy allocations are rejected before any insert`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.saveCategorizationResult(1L, result.copy(taxAllocation = null), 1_000L) }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `an allocation that does not conserve requested tax cannot be persisted`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.saveCategorizationResult(1L, result.copy(taxAllocation = allocation.copy(itemAmounts = allocation.itemAmounts.reversed().map { it.copy(amount = 0.1) })), 1_000L)
            }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `matching total does not permit assigning item tax to the wrong category`() = runTest {
        val tampered = allocation.copy(categoryAmounts = mapOf(2L to MoneyAmount(1.0, CurrencyCode.EUR)))

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.saveCategorizationResult(1L, result.copy(
                    taxDistribution = tampered.categoryAmounts.mapValues { it.value.amount }, taxAllocation = tampered
                ), 1_000L)
            }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `category and unallocated tax must use the requested currency`() = runTest {
        val cases = listOf(
            allocation.copy(categoryAmounts = mapOf(1L to MoneyAmount(1.0, CurrencyCode.USD))),
            allocation.copy(unallocatedTax = MoneyAmount(0.0, CurrencyCode.USD))
        )
        for (tampered in cases) {
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking {
                    repository.saveCategorizationResult(1L, result.copy(taxAllocation = tampered), 1_000L)
                }
            }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `legacy category map cannot disagree with the typed allocation`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.saveCategorizationResult(1L, result.copy(taxDistribution = mapOf(1L to 10.0)), 1_000L)
            }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `legacy results cannot persist an unconfirmed negative item`() = runTest {
        val invalid = result.copy(
            items = listOf(item(-1.0), item(2.0)),
            taxAllocation = null,
            taxDistribution = emptyMap()
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repository.saveCategorizationResult(1L, invalid, 1_000L) }
        }
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `confirmed signed item shares persist without category duplication`() = runTest {
        val signedItems = listOf(item(100.0), item(-20.0).copy(isAdjustment = true, suggestedCategory = null))
        val signedAllocation = ReceiptTaxAllocator.allocate(8.0, signedItems, "EUR") as ReceiptTaxAllocation.Available
        val signedResult = result.copy(
            items = signedItems, taxAllocation = signedAllocation,
            taxDistribution = signedAllocation.categoryAmounts.mapValues { it.value.amount }
        )
        val saved = mutableListOf<ReceiptItemCategorization>()
        coEvery { dao.insert(capture(saved)) } returns 1L

        assertThat(repository.saveCategorizationResult(1L, signedResult, 1_000L)).isEqualTo(2)
        assertThat(saved.map { it.taxAmount }).containsExactly(10.0, -2.0).inOrder()
        assertThat(signedAllocation.unallocatedTax.amount).isEqualTo(-2.0)
    }

    private fun item(amount: Double) = CategorizedReceiptItem(
        "item", amount, CategorySuggestion(1L, "Food", 0.9f), 0.9f, "matched", emptyList(), false
    )
}
