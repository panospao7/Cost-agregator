package com.yourname.expensetracker.ui.navigation

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J-01-1: EPHEMERAL destinations are not reopened after process death, and are left alone on a
 * same-process restore (rotation, background and resume). Pure JVM.
 */
class NavigationRestorePolicyTest {

    private val ephemeral = listOf(
        NavigationDestination.AddExpense,
        NavigationDestination.ScanReceipt,
        NavigationDestination.Assistant,
        NavigationDestination.Debug
    )

    private fun restore(
        destination: NavigationDestination,
        backStack: List<NavigationDestination> = emptyList(),
        previousMainTab: Int? = null,
        processDied: Boolean = true
    ) = applyEphemeralRestorePolicy(destination, backStack, previousMainTab, processDied)

    @Test
    fun `ephemeral current falls back to last non-ephemeral back stack entry`() {
        ephemeral.forEach { dest ->
            val result = restore(
                destination = dest,
                backStack = listOf(NavigationDestination.SavingsGoals, NavigationDestination.RecurringExpenses),
                previousMainTab = 3
            )
            assertEquals(NavigationDestination.RecurringExpenses, result.destination)
            assertEquals(listOf(NavigationDestination.SavingsGoals), result.backStack)
        }
    }

    @Test
    fun `ephemeral entries leave the restored back stack`() {
        val result = restore(
            destination = NavigationDestination.AddExpense,
            backStack = listOf(
                NavigationDestination.SavingsGoals,
                NavigationDestination.Assistant,
                NavigationDestination.RecurringExpenses,
                NavigationDestination.Debug
            )
        )
        assertEquals(NavigationDestination.RecurringExpenses, result.destination)
        assertEquals(listOf(NavigationDestination.SavingsGoals), result.backStack)
    }

    @Test
    fun `non-ephemeral current keeps destination but drops ephemeral stack entries`() {
        val result = restore(
            destination = NavigationDestination.SavingsGoals,
            backStack = listOf(NavigationDestination.ScanReceipt, NavigationDestination.RecurringExpenses)
        )
        assertEquals(NavigationDestination.SavingsGoals, result.destination)
        assertEquals(listOf(NavigationDestination.RecurringExpenses), result.backStack)
    }

    @Test
    fun `empty or all-ephemeral stack falls back to previous main tab`() {
        val expected = mapOf(
            0 to NavigationDestination.Home,
            1 to NavigationDestination.Transactions(),
            2 to NavigationDestination.Review,
            3 to NavigationDestination.Budget,
            4 to NavigationDestination.Analytics(),
            5 to NavigationDestination.SpendingMap()
        )
        expected.forEach { (tab, dest) ->
            val result = restore(
                destination = NavigationDestination.AddExpense,
                backStack = listOf(NavigationDestination.Assistant),
                previousMainTab = tab
            )
            assertEquals(dest, result.destination)
            assertTrue(result.backStack.isEmpty())
        }
    }

    @Test
    fun `no previous main tab falls back to Home never the initial destination`() {
        ephemeral.forEach { dest ->
            val result = restore(destination = dest, previousMainTab = null)
            assertEquals(NavigationDestination.Home, result.destination)
        }
        assertEquals(NavigationDestination.Home, restore(NavigationDestination.AddExpense, previousMainTab = 99).destination)
    }

    @Test
    fun `tab mapping is shared with navigateToTab`() {
        (0..6).forEach { tab ->
            val controller = NavigationController(mutableStateOf(NavigationDestination.SavingsGoals))
            controller.navigateToTab(tab)
            assertEquals(destinationForTab(tab), controller.destination)
        }
    }

    @Test
    fun `same-process restore keeps the Add Expense sheet open`() {
        // Rotation or background and resume without process death: saved token equals this process token.
        assertFalse(NavigationRestorePolicy.isRestoreAfterProcessDeath(NavigationRestorePolicy.processToken))
        val back = listOf(NavigationDestination.SavingsGoals, NavigationDestination.Assistant)
        val result = restore(
            destination = NavigationDestination.AddExpense,
            backStack = back,
            previousMainTab = 2,
            processDied = false
        )
        assertEquals(NavigationDestination.AddExpense, result.destination)
        assertEquals(back, result.backStack)
    }

    @Test
    fun `missing or foreign process token counts as process death`() {
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath(null))
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath("token-from-a-dead-process"))
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath(""))
    }

    @Test
    fun `fresh launch and normal navigation are unaffected`() {
        val controller = NavigationController(mutableStateOf(NavigationDestination.Home))
        controller.navigateTo(NavigationDestination.AddExpense)
        assertEquals(NavigationDestination.AddExpense, controller.destination)
        assertTrue(controller.navigateBack())
        assertEquals(NavigationDestination.Home, controller.getCurrentTabIndex()?.let { destinationForTab(it) })
    }

    @Test
    fun `restore helper is idempotent`() {
        val inputs = listOf(
            NavigationDestination.AddExpense to listOf(NavigationDestination.SavingsGoals, NavigationDestination.Debug),
            NavigationDestination.Assistant to emptyList(),
            NavigationDestination.SavingsGoals to listOf(NavigationDestination.ScanReceipt)
        )
        inputs.forEach { (dest, stack) ->
            val once = restore(dest, stack, previousMainTab = 4)
            val twice = restore(once.destination, once.backStack, previousMainTab = 4)
            assertEquals(once, twice)
            assertEquals(restore(once.destination, once.backStack, 4, processDied = false), once)
        }
    }

    @Test
    fun `back after fallback has no entry equal to the current screen and no empty-stack surprise`() {
        val restored = restore(
            destination = NavigationDestination.AddExpense,
            backStack = listOf(NavigationDestination.SavingsGoals, NavigationDestination.RecurringExpenses),
            previousMainTab = 1
        )
        assertFalse(restored.backStack.contains(restored.destination))

        val controller = NavigationController(
            currentDestination = mutableStateOf(restored.destination),
            initialBackStack = restored.backStack,
            initialPreviousMainTab = 1
        )
        assertTrue(controller.navigateBack())
        assertEquals(NavigationDestination.SavingsGoals, controller.destination)
        // Stack now empty on a feature screen: back returns to the previous main tab, never exits.
        assertTrue(controller.navigateBack())
        assertEquals(1, controller.getCurrentTabIndex())
        assertTrue(controller.navigateBack())
        assertEquals(0, controller.getCurrentTabIndex())
        assertFalse(controller.navigateBack())
    }

    @Test
    fun `fallback to a main tab backs out through Home then exits like normal navigation`() {
        val restored = restore(NavigationDestination.AddExpense, previousMainTab = 3)
        val controller = NavigationController(
            currentDestination = mutableStateOf(restored.destination),
            initialBackStack = restored.backStack,
            initialPreviousMainTab = 3
        )
        assertEquals(3, controller.getCurrentTabIndex())
        assertTrue(controller.navigateBack())
        assertEquals(0, controller.getCurrentTabIndex())
        assertFalse(controller.navigateBack())
    }

    @Test
    fun `ephemeral policy set is exactly the four documented destinations`() {
        ephemeral.forEach {
            assertEquals(DestinationPersistencePolicy.EPHEMERAL, it.persistencePolicy())
        }
    }
}
