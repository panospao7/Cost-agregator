package com.yourname.expensetracker.ui.screens.groups

import com.yourname.expensetracker.data.database.entity.ExpenseGroup
import com.yourname.expensetracker.data.database.entity.GroupExpense
import com.yourname.expensetracker.data.database.entity.GroupMember
import com.yourname.expensetracker.data.database.entity.SplitType
import com.yourname.expensetracker.domain.groups.GroupSettlementBalancePolicy
import com.yourname.expensetracker.domain.groups.SharedGroupSettlement
import com.yourname.expensetracker.domain.logic.SplitCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedExpenseGroupsScreenStateTest {

    @Test
    fun `active member projection excludes departed members while preserving history`() {
        val alice = GroupMember(id = 1L, groupId = 7L, name = "Alice")
        val bob = GroupMember(id = 2L, groupId = 7L, name = "Bob", leftAt = 123L)
        val group = GroupWithDetails(
            group = ExpenseGroup(id = 7L, name = "Trip"),
            members = listOf(alice, bob),
            expenses = emptyList(),
            displayed = emptyAvailableBalances()
        )

        assertEquals(listOf(alice, bob), group.members)
        assertEquals(listOf(alice), group.activeMembers)
    }

    @Test
    fun `active member projection is empty when all members departed or absent`() {
        val departed = GroupMember(id = 3L, groupId = 8L, name = "Carol", leftAt = 456L)
        val allDeparted = GroupWithDetails(
            group = ExpenseGroup(id = 8L, name = "Old Trip"),
            members = listOf(departed),
            expenses = emptyList(),
            displayed = emptyAvailableBalances()
        )
        val empty = allDeparted.copy(members = emptyList())

        assertEquals(listOf(departed), allDeparted.members)
        assertTrue(empty.members.isEmpty())
        assertTrue(allDeparted.activeMembers.isEmpty())
        assertTrue(empty.activeMembers.isEmpty())
    }

    @Test
    fun `isSettledBalance treats near-zero values as settled at currency precision`() {
        assertTrue(isSettledBalance(0.004, fractionDigits = 2))
        assertTrue(isSettledBalance(-0.004, fractionDigits = 2))
        assertFalse(isSettledBalance(0.005, fractionDigits = 2))
        assertFalse(isSettledBalance(-0.005, fractionDigits = 2))
    }

    @Test
    fun `roundedBalanceForDisplay rounds to requested fraction digits`() {
        assertEquals(1.24, roundedBalanceForDisplay(1.235, fractionDigits = 2), 0.0)
        assertEquals(-1.24, roundedBalanceForDisplay(-1.235, fractionDigits = 2), 0.0)
        assertEquals(0.01, roundedBalanceForDisplay(0.005, fractionDigits = 2), 0.0)
        assertEquals(-0.01, roundedBalanceForDisplay(-0.005, fractionDigits = 2), 0.0)
        assertEquals(10.0, roundedBalanceForDisplay(9.6, fractionDigits = 0), 0.0)
    }

    private fun emptyAvailableBalances() = GroupSettlementBalancePolicy.DisplayedBalances(
        availability = GroupSettlementBalancePolicy.Availability.AVAILABLE,
        currency = "EUR",
        includedTotal = 0.0,
        memberBalances = emptyMap(),
        excludedExpenseCount = 0,
        excludedSettlementCount = 0
    )

    private val alex = GroupMember(id = 1L, groupId = 7L, name = "Alex", isCurrentUser = true)
    private val sam = GroupMember(id = 2L, groupId = 7L, name = "Sam")

    private fun expense(id: Long, paidById: Long, amount: Double, currency: String) = GroupExpense(
        id = id,
        groupId = 7L,
        expenseId = id + 1000,
        paidById = paidById,
        date = 1_700_000_000_000L,
        description = "Synthetic dinner",
        totalAmount = amount,
        currency = currency,
        splitType = SplitType.EQUAL
    )

    private fun displayed(
        groupCurrency: String,
        expenses: List<GroupExpense>,
        settlements: List<SharedGroupSettlement> = emptyList()
    ) = GroupSettlementBalancePolicy.displayedBalances(
        groupId = 7L,
        groupCurrency = groupCurrency,
        expenses = expenses,
        members = listOf(alex, sam),
        settlements = settlements
    )

    // ---- AE-7 / INV-7: the suggested plan is composed only for an AVAILABLE group ----

    @Test
    fun `AE-7 partial group composes no settlement plan`() {
        val partial = displayed(
            "EUR",
            listOf(expense(1L, 1L, 30.0, "EUR"), expense(2L, 2L, 20.0, "USD"))
        )

        assertEquals(GroupSettlementBalancePolicy.Availability.PARTIAL, partial.availability)
        assertNull(suggestedSettlementTransfers(partial))
    }

    @Test
    fun `AE-7 unavailable group composes no settlement plan`() {
        val unavailable = displayed("", listOf(expense(1L, 1L, 10.0, "")))

        assertEquals(GroupSettlementBalancePolicy.Availability.UNAVAILABLE, unavailable.availability)
        assertNull(suggestedSettlementTransfers(unavailable))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `AE-7 available group composes the plan equal to simplifyBalances of its balances`() {
        val available = displayed("EUR", listOf(expense(1L, 1L, 30.0, "EUR")))

        assertEquals(GroupSettlementBalancePolicy.Availability.AVAILABLE, available.availability)
        val transfers = suggestedSettlementTransfers(available)
        assertEquals(SplitCalculator.simplifyBalances(available.memberBalances), transfers)
        assertEquals(1, transfers!!.size)
    }

    // ---- AE-8 / INV-8: one combined count notice with no identifying text ----

    @Test
    fun `AE-8 notice count is excluded expenses plus excluded settlements`() {
        val partial = displayed(
            "EUR",
            listOf(expense(1L, 1L, 30.0, "EUR"), expense(2L, 2L, 20.0, "USD")),
            listOf(SharedGroupSettlement(7L, 2L, 1L, 5.0, "GBP", "COMPLETED"))
        )

        assertEquals(1, partial.excludedExpenseCount)
        assertEquals(1, partial.excludedSettlementCount)
        assertEquals(2, leftOutItemCount(partial))
    }

    @Test
    fun `AE-8 notice count is zero for an available group`() {
        assertEquals(0, leftOutItemCount(displayed("EUR", listOf(expense(1L, 1L, 30.0, "EUR")))))
    }

    @Test
    fun `AE-8 notice copy names no currency and carries only the count in every locale`() {
        listOf("values", "values-el", "values-es").forEach { folder ->
            val strings = readStringsXml(folder)
            val plural = Regex(
                """<plurals name="money_total_partial_notice">(.*?)</plurals>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(strings)?.groupValues?.get(1)
            assertNotNull("$folder is missing the partial notice plural", plural)
            val items = Regex("""<item quantity="[a-z]+">(.*?)</item>""").findAll(plural!!)
                .map { it.groupValues[1] }.toList()
            assertTrue("$folder has no plural items", items.isNotEmpty())
            items.forEach { text ->
                val lower = text.lowercase()
                listOf("currenc", "νόμισμ", "moneda", "divisa").forEach { word ->
                    assertFalse("$folder notice must not mention a currency: $text", lower.contains(word))
                }
                // Only the count placeholder: no description, amount or member-name slots.
                assertEquals(1, Regex("%").findAll(text).count())
                assertTrue(text.contains("%1\$d"))
            }
        }
    }

    // ---- AE-11 / INV-11: create-group currency selection ----

    @Test
    fun `AE-11 no currency is selected when the home currency is unknown`() {
        assertNull(initialGroupCurrencySelection(null))
        assertNull(initialGroupCurrencySelection(""))
        assertNull(initialGroupCurrencySelection("XXX"))
        assertFalse(canCreateGroup("Trip", initialGroupCurrencySelection(null)))
    }

    @Test
    fun `AE-11 create stays disabled until a supported currency is picked`() {
        assertFalse(canCreateGroup("Trip", null))
        assertFalse(canCreateGroup("Trip", ""))
        assertFalse(canCreateGroup("Trip", "XXX"))
        assertFalse(canCreateGroup("   ", "GBP"))
        assertTrue(canCreateGroup("Trip", "GBP"))
    }

    @Test
    fun `AE-11 a supported home currency pre-selects and enables create`() {
        assertEquals("GBP", initialGroupCurrencySelection("GBP"))
        assertTrue(canCreateGroup("Trip", initialGroupCurrencySelection("GBP")))
    }

    // ---- UNAVAILABLE is never a number ----

    @Test
    fun `unavailable group carries no total and no balances`() {
        val unavailable = displayed("", listOf(expense(1L, 1L, 10.0, "")))

        assertNull(unavailable.includedTotal)
        assertTrue(unavailable.memberBalances.isEmpty())
    }

    private fun readStringsXml(folder: String): String {
        val candidates = listOf(
            File("src/main/res/$folder/strings.xml"),
            File("app/src/main/res/$folder/strings.xml")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("strings.xml not found for $folder", file)
        return file!!.readText(Charsets.UTF_8)
    }
}
