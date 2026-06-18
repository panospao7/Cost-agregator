package com.yourname.expensetracker.ui.navigation

import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Behavior tests for NavigationController.
 *
 * Validates back stack, tab switching, feature navigation, and edge cases
 * without any Android/Compose dependencies (pure JVM).
 */
class NavigationControllerBehaviorTest {

    private lateinit var controller: NavigationController

    @Before
    fun setUp() {
        controller = NavigationController(
            currentDestination = mutableStateOf(NavigationDestination.Home)
        )
    }

    // ── Initial state ──

    @Test
    fun `initial state is Home with no back`() {
        assertEquals(NavigationDestination.Home, controller.destination)
        assertTrue(controller.isOnMainTab())
        assertEquals(0, controller.getCurrentTabIndex())
        assertFalse(controller.navigateBack()) // Home = can't go further back
    }

    // ── Tab switching ──

    @Test
    fun `tab switch changes destination`() {
        controller.navigateToTab(1)
        assertEquals(1, controller.getCurrentTabIndex())
        assertTrue(controller.isOnMainTab())
    }

    @Test
    fun `back from non-home tab returns to Home`() {
        controller.navigateToTab(2) // Review
        assertEquals(2, controller.getCurrentTabIndex())

        val handled = controller.navigateBack()
        assertTrue(handled)
        assertEquals(0, controller.getCurrentTabIndex()) // Back to Home
    }

    @Test
    fun `back from Home returns false`() {
        assertFalse(controller.navigateBack())
    }

    @Test
    fun `invalid tab index falls back to Home`() {
        controller.navigateToTab(99)
        assertEquals(NavigationDestination.Home, controller.destination)
        assertEquals(0, controller.getCurrentTabIndex())
    }

    // ── Feature navigation from Home ──

    @Test
    fun `feature from Home backs to Home`() {
        controller.navigateTo(NavigationDestination.SavingsGoals)

        assertFalse(controller.isOnMainTab())
        assertNull(controller.getCurrentTabIndex())

        val handled = controller.navigateBack()
        assertTrue(handled)
        assertEquals(0, controller.getCurrentTabIndex())
    }

    // ── Feature navigation from other tab ──

    @Test
    fun `feature from Transactions backs to Transactions`() {
        controller.navigateToTab(1) // Transactions
        controller.navigateTo(NavigationDestination.ExportOptions)

        assertFalse(controller.isOnMainTab())

        val handled = controller.navigateBack()
        assertTrue(handled)
        // Should return to previous main tab (Transactions)
        assertEquals(1, controller.getCurrentTabIndex())
    }

    // ── Feature-to-feature stack ──

    @Test
    fun `feature to feature uses back stack`() {
        controller.navigateTo(NavigationDestination.SavingsGoals)
        controller.navigateTo(NavigationDestination.BackupRestore)

        assertEquals(NavigationDestination.BackupRestore, controller.destination)

        // First back: SavingsGoals
        controller.navigateBack()
        assertEquals(NavigationDestination.SavingsGoals, controller.destination)

        // Second back: Home (previous main tab)
        controller.navigateBack()
        assertEquals(0, controller.getCurrentTabIndex())
    }

    // ── Tab switch clears feature stack ──

    @Test
    fun `tab switch clears feature back stack`() {
        controller.navigateTo(NavigationDestination.SavingsGoals)
        controller.navigateTo(NavigationDestination.BackupRestore)

        // Switch to Analytics tab
        controller.navigateToTab(4)
        assertEquals(4, controller.getCurrentTabIndex())

        // Back should go to Home (not SavingsGoals)
        controller.navigateBack()
        assertEquals(0, controller.getCurrentTabIndex())
    }

    // ── navigateHome ──

    @Test
    fun `navigateHome clears stack and goes Home`() {
        controller.navigateToTab(3)
        controller.navigateTo(NavigationDestination.BudgetForecasting())
        controller.navigateTo(NavigationDestination.ExportOptions)

        controller.navigateHome()

        assertEquals(NavigationDestination.Home, controller.destination)
        assertFalse(controller.navigateBack()) // Stack cleared
    }

    // ── canNavigateBack ──

    @Test
    fun `canNavigateBack true on non-home tab`() {
        controller.navigateToTab(2)
        assertTrue(controller.canNavigateBack())
    }

    @Test
    fun `canNavigateBack true on feature screen`() {
        controller.navigateTo(NavigationDestination.SavingsGoals)
        assertTrue(controller.canNavigateBack())
    }

    @Test
    fun `canNavigateBack false on Home`() {
        assertFalse(controller.canNavigateBack())
    }
}
