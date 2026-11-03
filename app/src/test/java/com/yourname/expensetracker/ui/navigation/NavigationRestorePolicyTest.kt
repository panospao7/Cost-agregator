package com.yourname.expensetracker.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J-01-1: AddExpense is not reopened after process death (fallback); Scan Receipt, Assistant and
 * Debug restore unchanged. Same-process restores are left alone (rotation, background and resume).
 * Pure JVM.
 */
class NavigationRestorePolicyTest {

    private val restorable = listOf(
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

    private fun saveAndRestore(state: PersistedNavigationState): PersistedNavigationState {
        val saver = PersistedNavigationState.Saver
        val saved = with(saver) { SaverScope { true }.save(state) }
        assertNotNull(saved)
        return saver.restore(saved!!)!!
    }

    @Test
    fun `add expense current falls back to last remaining back stack entry`() {
        val result = restore(
            destination = NavigationDestination.AddExpense,
            backStack = listOf(NavigationDestination.SavingsGoals, NavigationDestination.RecurringExpenses),
            previousMainTab = 3
        )
        assertEquals(NavigationDestination.RecurringExpenses, result.destination)
        assertEquals(listOf(NavigationDestination.SavingsGoals), result.backStack)
    }

    @Test
    fun `add expense entries leave the restored back stack and other ephemeral entries stay`() {
        val result = restore(
            destination = NavigationDestination.AddExpense,
            backStack = listOf(
                NavigationDestination.SavingsGoals,
                NavigationDestination.AddExpense,
                NavigationDestination.RecurringExpenses,
                NavigationDestination.Debug
            )
        )
        assertEquals(NavigationDestination.Debug, result.destination)
        assertEquals(
            listOf(NavigationDestination.SavingsGoals, NavigationDestination.RecurringExpenses),
            result.backStack
        )
    }

    @Test
    fun `scan receipt assistant and debug restore unchanged as current with their back stack`() {
        val back = listOf(
            NavigationDestination.SavingsGoals,
            NavigationDestination.ScanReceipt,
            NavigationDestination.Assistant,
            NavigationDestination.Debug,
            NavigationDestination.RecurringExpenses
        )
        restorable.forEach { dest ->
            val result = restore(destination = dest, backStack = back, previousMainTab = 3)
            assertEquals(dest, result.destination)
            assertEquals(back, result.backStack)
        }
    }

    @Test
    fun `scan receipt current with empty back stack stays on scan receipt`() {
        val result = restore(destination = NavigationDestination.ScanReceipt, previousMainTab = 2)
        assertEquals(NavigationDestination.ScanReceipt, result.destination)
        assertTrue(result.backStack.isEmpty())
    }

    @Test
    fun `non-add-expense current keeps destination and ephemeral stack entries but drops add expense`() {
        val result = restore(
            destination = NavigationDestination.SavingsGoals,
            backStack = listOf(
                NavigationDestination.ScanReceipt,
                NavigationDestination.AddExpense,
                NavigationDestination.RecurringExpenses
            )
        )
        assertEquals(NavigationDestination.SavingsGoals, result.destination)
        assertEquals(
            listOf(NavigationDestination.ScanReceipt, NavigationDestination.RecurringExpenses),
            result.backStack
        )
    }

    @Test
    fun `add expense with empty stack falls back to previous main tab`() {
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
                backStack = listOf(NavigationDestination.AddExpense),
                previousMainTab = tab
            )
            assertEquals(dest, result.destination)
            assertTrue(result.backStack.isEmpty())
        }
    }

    @Test
    fun `no previous main tab falls back to Home never the initial destination`() {
        assertEquals(NavigationDestination.Home, restore(NavigationDestination.AddExpense, previousMainTab = null).destination)
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
        val back = listOf(NavigationDestination.SavingsGoals, NavigationDestination.AddExpense)
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
    fun `fromDestination and fromSnapshot stamp the current process token`() {
        val fromDestination = PersistedNavigationState.fromDestination(NavigationDestination.AddExpense)
        assertEquals(NavigationRestorePolicy.processToken, fromDestination.processToken)

        val fromSnapshot = PersistedNavigationState.fromSnapshot(
            NavigationControllerSnapshot(
                destination = NavigationDestination.AddExpense,
                backStack = listOf(NavigationDestination.SavingsGoals),
                previousMainTab = 2
            )
        )
        assertEquals(NavigationRestorePolicy.processToken, fromSnapshot.processToken)
        assertEquals(listOf("savings_goals"), fromSnapshot.backStackTokens)
    }

    @Test
    fun `saver round trip keeps the process token so a rotation is not treated as process death`() {
        val states = listOf(
            PersistedNavigationState.fromDestination(NavigationDestination.AddExpense),
            PersistedNavigationState.fromSnapshot(
                NavigationControllerSnapshot(
                    destination = NavigationDestination.ScanReceipt,
                    backStack = listOf(NavigationDestination.SavingsGoals, NavigationDestination.AddExpense),
                    previousMainTab = 3
                )
            )
        )
        states.forEach { state ->
            val restored = saveAndRestore(state)
            assertEquals(state, restored)
            assertEquals(NavigationRestorePolicy.processToken, restored.processToken)
            assertFalse(NavigationRestorePolicy.isRestoreAfterProcessDeath(restored.processToken))
        }
    }

    @Test
    fun `saver restore of a state without token or with a foreign token counts as process death`() {
        val noToken = saveAndRestore(
            PersistedNavigationState("add_expense", emptyList(), null, processToken = null)
        )
        assertEquals(null, noToken.processToken)
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath(noToken.processToken))

        val foreign = saveAndRestore(
            PersistedNavigationState("add_expense", emptyList(), null, processToken = "dead-process")
        )
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath(foreign.processToken))

        // Legacy three-element saved list (written before the token existed) fails closed.
        val legacy = PersistedNavigationState.Saver.restore(listOf("add_expense", emptyList<String>(), null))!!
        assertEquals(null, legacy.processToken)
        assertTrue(NavigationRestorePolicy.isRestoreAfterProcessDeath(legacy.processToken))
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
            NavigationDestination.ScanReceipt to listOf(NavigationDestination.SavingsGoals),
            NavigationDestination.SavingsGoals to listOf(NavigationDestination.ScanReceipt, NavigationDestination.AddExpense)
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
    fun `persistence policy still declares four ephemeral destinations but only add expense falls back`() {
        listOf(
            NavigationDestination.AddExpense,
            NavigationDestination.ScanReceipt,
            NavigationDestination.Assistant,
            NavigationDestination.Debug
        ).forEach {
            assertEquals(DestinationPersistencePolicy.EPHEMERAL, it.persistencePolicy())
        }
        assertTrue(NavigationDestination.AddExpense.fallsBackAfterProcessDeath())
        restorable.forEach { assertFalse(it.fallsBackAfterProcessDeath()) }
    }
}
