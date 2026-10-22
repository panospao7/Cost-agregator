package com.yourname.expensetracker.domain.groups

import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.domain.groups.GroupSettlementBalancePolicy.Availability
import com.yourname.expensetracker.domain.logic.SplitCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.test.assertFailsWith

class GroupSettlementBalancePolicyTest {
    private val balances = mapOf(1L to -50.0, 2L to 50.0)
    private fun payment(amount: Double, status: String = "RECORDED") =
        SharedGroupSettlement(7L, 1L, 2L, amount, "EUR", status)

    @Test
    fun partialAndMultiplePaymentsMoveBothSidesTowardZero() {
        assertEquals(mapOf(1L to -30.0, 2L to 30.0),
            GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(20.0)), 7L, "EUR"))
        val result = GroupSettlementBalancePolicy.applyToBalances(
            balances, listOf(payment(20.0), payment(30.0, "COMPLETED")), 7L, "EUR")
        assertEquals(mapOf(1L to 0.0, 2L to 0.0), result)
        assertEquals(0.0, result.values.sum(), 0.0)
    }

    @Test
    fun unknownCancelledForeignAndOtherGroupPaymentsAreExcluded() {
        val excluded = listOf(payment(10.0, "UNKNOWN"), payment(10.0, "CANCELLED"),
            payment(10.0).copy(currency = "USD"), payment(10.0).copy(groupId = 8L))
        assertEquals(balances, GroupSettlementBalancePolicy.applyToBalances(balances, excluded, 7L, "EUR"))
    }

    @Test
    fun zeroAndOverpaymentsAreNotClamped() {
        assertEquals(balances, GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(0.0)), 7L, "EUR"))
        assertEquals(mapOf(1L to 10.0, 2L to -10.0),
            GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(60.0)), 7L, "EUR"))
    }

    @Test
    fun decimalAccumulationDoesNotIntroduceFloatingPointResidue() {
        val result = GroupSettlementBalancePolicy.applyToBalances(
            mapOf(1L to -0.3, 2L to 0.3), listOf(payment(0.1), payment(0.2)), 7L, "EUR")
        assertTrue(result.values.all { it == 0.0 })
    }

    @Test
    fun nonFiniteEffectiveAmountsFailInsteadOfHidingPaymentHistory() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { amount ->
            assertFailsWith<NumberFormatException> {
                GroupSettlementBalancePolicy.applyToBalances(balances, listOf(payment(amount)), 7L, "EUR")
            }
        }
    }

    // ---- displayedBalances (money-aggregates-sweep S1) ----

    private val memberA = GroupMember(id = 1L, groupId = 7L, name = "A", joinedAt = 0L)
    private val memberB = GroupMember(id = 2L, groupId = 7L, name = "B", joinedAt = 0L)
    private val groupMembers = listOf(memberA, memberB)

    private fun groupExpense(
        id: Long,
        amount: Double,
        currency: String = "EUR",
        paidById: Long = 1L
    ) = GroupExpense(
        id = id,
        groupId = 7L,
        expenseId = null,
        paidById = paidById,
        date = 1_000L,
        description = "fixture-$id",
        totalAmount = amount,
        currency = currency
    )

    private fun settlementOf(
        amount: Double,
        currency: String = "EUR",
        status: String = "RECORDED",
        groupId: Long = 7L,
        from: Long = 2L,
        to: Long = 1L
    ) = SharedGroupSettlement(groupId, from, to, amount, currency, status)

    private fun displayed(
        expenses: List<GroupExpense>,
        settlements: List<SharedGroupSettlement> = emptyList(),
        groupCurrency: String = "EUR",
        members: List<GroupMember> = groupMembers
    ) = GroupSettlementBalancePolicy.displayedBalances(7L, groupCurrency, expenses, members, settlements)

    @Test
    fun ae2_foreignExpenseIsLeftOutOfTotalAndBalancesAndCounted() {
        val result = displayed(
            listOf(groupExpense(1L, 30.0), groupExpense(2L, 20.0, currency = "USD", paidById = 2L))
        )
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals("EUR", result.currency)
        assertEquals(1, result.excludedExpenseCount)
        assertEquals(0, result.excludedSettlementCount)
        assertEquals(30.0, result.includedTotal!!, 0.0)
        assertEquals(mapOf(1L to 15.0, 2L to -15.0), result.memberBalances)
        assertFalse(result.settlementPlanAllowed)
    }

    @Test
    fun ae3_onlyConsideredForeignSettlementIsCountedAndNotApplied() {
        val settlements = listOf(
            settlementOf(15.0, "EUR", "RECORDED"),
            settlementOf(5.0, "GBP", "COMPLETED"),
            settlementOf(10.0, "GBP", "CANCELLED"),
            settlementOf(10.0, "GBP", "UNKNOWN"),
            settlementOf(10.0, "GBP", "RECORDED", groupId = 8L)
        )
        val result = displayed(listOf(groupExpense(1L, 30.0)), settlements)
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals(1, result.excludedSettlementCount)
        assertEquals(0, result.excludedExpenseCount)
        assertEquals(mapOf(1L to 0.0, 2L to 0.0), result.memberBalances)
        assertEquals(30.0, result.includedTotal!!, 0.0)
        assertFalse(result.settlementPlanAllowed)
    }

    @Test
    fun ae4_blankGroupCurrencyIsUnavailableWithNullTotalAndNoBalances() {
        val result = displayed(listOf(groupExpense(1L, 10.0, currency = "")), groupCurrency = "")
        assertEquals(Availability.UNAVAILABLE, result.availability)
        assertNull(result.includedTotal)
        assertEquals(emptyMap<Long, Double>(), result.memberBalances)
        assertFalse(result.settlementPlanAllowed)
        assertEquals(1, result.excludedExpenseCount)
    }

    @Test
    fun ae4_unavailableCountsAllExpensesAndOnlyConsideredSettlements() {
        val result = displayed(
            expenses = listOf(groupExpense(1L, 10.0, "EUR"), groupExpense(2L, 5.0, "USD")),
            settlements = listOf(
                settlementOf(1.0, "EUR", "RECORDED"),
                settlementOf(1.0, "USD", "COMPLETED"),
                settlementOf(1.0, "EUR", "CANCELLED"),
                settlementOf(1.0, "EUR", "RECORDED", groupId = 8L)
            ),
            groupCurrency = ""
        )
        assertEquals(Availability.UNAVAILABLE, result.availability)
        assertEquals(2, result.excludedExpenseCount)
        assertEquals(2, result.excludedSettlementCount)
        assertNull(result.includedTotal)
        assertTrue(result.memberBalances.isEmpty())
    }

    @Test
    fun ae4_unknownOrNonCanonicalGroupCurrencyIsUnavailableAndInactiveCodeIsKnown() {
        listOf("XXX", "eur", " EUR", "EURO", "E1R").forEach { code ->
            val result = displayed(listOf(groupExpense(1L, 10.0, currency = code)), groupCurrency = code)
            assertEquals(code, Availability.UNAVAILABLE, result.availability)
            assertNull(code, result.includedTotal)
        }
        val inactive = displayed(listOf(groupExpense(1L, 10.0, currency = "HRK")), groupCurrency = "HRK")
        assertEquals(Availability.AVAILABLE, inactive.availability)
        assertEquals(10.0, inactive.includedTotal!!, 0.0)
    }

    @Test
    fun ae4_onlyGroupCurrencyRowsWithSettlementIsAvailableAndPlanAllowed() {
        val result = displayed(
            listOf(groupExpense(1L, 30.0), groupExpense(2L, 10.0, paidById = 2L)),
            listOf(settlementOf(5.0))
        )
        assertEquals(Availability.AVAILABLE, result.availability)
        assertEquals(0, result.excludedExpenseCount)
        assertEquals(0, result.excludedSettlementCount)
        assertTrue(result.settlementPlanAllowed)
        assertNotNull(result.includedTotal)
    }

    @Test
    fun ae5_includedTotalIsBitIdenticalToPlainDoubleSum() {
        val amounts = listOf(10.10, 20.20)
        val result = displayed(amounts.mapIndexed { i, amount -> groupExpense(i + 1L, amount) })
        assertEquals(amounts.sumOf { it }.toRawBits(), result.includedTotal!!.toRawBits())
    }

    @Test
    fun ae5_includedTotalNeverContainsExcludedRows() {
        val result = displayed(
            listOf(groupExpense(1L, 10.10), groupExpense(2L, 99.99, currency = "USD"), groupExpense(3L, 20.20))
        )
        assertEquals(listOf(10.10, 20.20).sumOf { it }.toRawBits(), result.includedTotal!!.toRawBits())
    }

    @Test
    fun ae6_availableResultsEqualExistingTotalsAndApplyPathForAllEurFixtures() {
        val rnd = Random(20261022)
        val statuses = listOf("RECORDED", "COMPLETED", "CANCELLED", "UNKNOWN")
        val lateMember = GroupMember(id = 3L, groupId = 7L, name = "C", joinedAt = rnd.nextLong(0L, 2_000L))
        val members = groupMembers + lateMember
        repeat(200) { iteration ->
            val expenses = (0 until rnd.nextInt(0, 7)).map { n ->
                groupExpense(n + 1L, rnd.nextInt(1, 100_000) / 100.0, paidById = rnd.nextLong(1L, 3L))
            }
            val settlements = (0 until rnd.nextInt(0, 5)).map {
                settlementOf(
                    amount = rnd.nextInt(1, 50_000) / 100.0,
                    status = statuses[rnd.nextInt(statuses.size)],
                    groupId = if (rnd.nextInt(4) == 0) 8L else 7L,
                    from = rnd.nextLong(1L, 4L),
                    to = rnd.nextLong(1L, 4L)
                )
            }
            val expectedBalances = GroupSettlementBalancePolicy.applyToBalances(
                SplitCalculator.calculateBalances(expenses, members), settlements, 7L, "EUR"
            )
            val result = displayed(expenses, settlements, members = members)
            assertEquals("iteration $iteration", Availability.AVAILABLE, result.availability)
            assertEquals("iteration $iteration", expectedBalances, result.memberBalances)
            assertEquals(
                "iteration $iteration",
                expenses.sumOf { it.totalAmount }.toRawBits(),
                result.includedTotal!!.toRawBits()
            )
            assertEquals(0, result.excludedExpenseCount)
            assertEquals(0, result.excludedSettlementCount)
        }
    }

    @Test
    fun ae6_partialResultEqualsExistingPathOverTheIncludedSubsetOnly() {
        val included = listOf(groupExpense(1L, 33.33), groupExpense(3L, 12.5, paidById = 2L))
        val foreign = listOf(groupExpense(2L, 77.0, currency = "USD"))
        val settlements = listOf(settlementOf(4.0), settlementOf(9.0, "USD"))
        val result = displayed(listOf(included[0], foreign[0], included[1]), settlements)
        val expected = GroupSettlementBalancePolicy.applyToBalances(
            SplitCalculator.calculateBalances(included, groupMembers), settlements, 7L, "EUR"
        )
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals(expected, result.memberBalances)
        assertEquals(1, result.excludedExpenseCount)
        assertEquals(1, result.excludedSettlementCount)
    }

    @Test
    fun ae9_totalsAndApplyToBalancesBehaviourIsUnchangedByDisplayedBalances() {
        val settlements = listOf(
            settlementOf(15.0), settlementOf(5.0, "GBP", "COMPLETED"), settlementOf(10.0, status = "CANCELLED")
        )
        val before = GroupSettlementBalancePolicy.totals(settlements, 7L, "EUR")
        displayed(listOf(groupExpense(1L, 30.0)), settlements)
        val after = GroupSettlementBalancePolicy.totals(settlements, 7L, "EUR")
        assertEquals(before, after)
        assertEquals(setOf(1L, 2L), after.keys)
        assertEquals(BigDecimal.valueOf(15.0), after.getValue(2L).paid)
        assertEquals(BigDecimal.ZERO, after.getValue(2L).received)
        assertEquals(BigDecimal.valueOf(15.0), after.getValue(1L).received)
        // Foreign settlements stay silently skipped by the legacy contract (no exception, no entry).
        assertEquals(
            balances,
            GroupSettlementBalancePolicy.applyToBalances(balances, listOf(settlementOf(5.0, "GBP")), 7L, "EUR")
        )
    }

    @Test
    fun emptyGroupIsAvailableWithZeroTotalAndZeroBalances() {
        val result = displayed(emptyList())
        assertEquals(Availability.AVAILABLE, result.availability)
        assertEquals(0.0, result.includedTotal!!, 0.0)
        assertEquals(mapOf(1L to 0.0, 2L to 0.0), result.memberBalances)
        assertEquals(0, result.excludedExpenseCount)
        assertEquals(0, result.excludedSettlementCount)
        assertTrue(result.settlementPlanAllowed)
    }

    @Test
    fun allExpensesExcludedWithSameCurrencySettlementIsPartialWithZeroTotal() {
        val result = displayed(
            listOf(groupExpense(1L, 20.0, currency = "USD", paidById = 2L)),
            listOf(settlementOf(5.0))
        )
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals(0.0, result.includedTotal!!, 0.0)
        assertEquals(1, result.excludedExpenseCount)
        assertEquals(0, result.excludedSettlementCount)
        assertEquals(mapOf(1L to -5.0, 2L to 5.0), result.memberBalances)
        assertFalse(result.settlementPlanAllowed)
    }

    @Test
    fun lowerCaseRowCurrencyIsExcludedAndCountedAgainstUpperCaseGroup() {
        val result = displayed(
            listOf(groupExpense(1L, 30.0), groupExpense(2L, 8.0, currency = "eur")),
            listOf(settlementOf(2.0, currency = "eur"))
        )
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals(1, result.excludedExpenseCount)
        assertEquals(1, result.excludedSettlementCount)
        assertEquals(30.0, result.includedTotal!!, 0.0)
        assertEquals(mapOf(1L to 15.0, 2L to -15.0), result.memberBalances)
    }

    @Test
    fun nonFiniteAmountsInExcludedRowsAreCountedWithoutBeingEvaluated() {
        val result = displayed(
            listOf(groupExpense(1L, 30.0), groupExpense(2L, Double.NaN, currency = "USD")),
            listOf(settlementOf(Double.NaN, currency = "GBP"), settlementOf(Double.POSITIVE_INFINITY, "USD"))
        )
        assertEquals(Availability.PARTIAL, result.availability)
        assertEquals(1, result.excludedExpenseCount)
        assertEquals(2, result.excludedSettlementCount)
        assertEquals(30.0, result.includedTotal!!, 0.0)
        assertEquals(mapOf(1L to 15.0, 2L to -15.0), result.memberBalances)
    }

    @Test
    fun nonFiniteAmountOnIncludedSettlementStillFailsInsteadOfHidingHistory() {
        assertFailsWith<NumberFormatException> {
            displayed(listOf(groupExpense(1L, 30.0)), listOf(settlementOf(Double.NaN)))
        }
    }
}
