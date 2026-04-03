package com.yourname.expensetracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque

/**
 * Controller for managing navigation state and providing navigation actions.
 * Use [LocalNavigationController] to access the controller in Composables.
 * 
 * Example usage:
 * ```kotlin
 * val navigation = LocalNavigationController.current
 * navigation.navigateTo(NavigationDestination.AddExpense)
 * ```
 */
class NavigationController(
    private val currentDestination: MutableState<NavigationDestination>
) {
    private val backStack = ArrayDeque<NavigationDestination>()
    private val _navigationEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    val navigationEvents: SharedFlow<NavigationEvent> = _navigationEvents.asSharedFlow()
    
    // Track the previous main tab to return to when navigating back from feature screens
    var previousMainTab: Int? = null
        private set
    
    val destination: NavigationDestination
        get() = currentDestination.value
    
    /**
     * Navigate to a specific destination.
     * Saves current destination to back stack if it's a feature screen.
     * Also saves the current main tab index when navigating away from a main tab.
     */
    fun navigateTo(destination: NavigationDestination) {
        val current = currentDestination.value
        
        // Save current main tab before navigating away from it
        if (isMainTab(current)) {
            previousMainTab = getCurrentTabIndex()
        }
        
        // Only add to back stack if current is a feature (not main tabs)
        if (!isMainTab(current)) {
            backStack.addLast(current)
        }
        
        currentDestination.value = destination
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(destination))
    }
    
    /**
     * Navigate back to the previous destination.
     * If no previous destination exists, navigates to the previous main tab or Home.
     */
    fun navigateBack(): Boolean {
        return if (backStack.isNotEmpty()) {
            val previous = backStack.removeLast()
            currentDestination.value = previous
            _navigationEvents.tryEmit(NavigationEvent.NavigateBack)
            true
        } else {
            // No back stack entry, go to previous main tab or Home
            val targetTab = previousMainTab ?: 0
            navigateToTab(targetTab)
            false
        }
    }
    
    /**
     * Navigate to Home tab and clear feature back stack.
     */
    fun navigateHome() {
        backStack.clear()
        currentDestination.value = NavigationDestination.Home
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(NavigationDestination.Home))
    }
    
    /**
     * Navigate to a specific tab by index (0-5).
     * This clears the feature back stack and updates the destination.
     */
    fun navigateToTab(tabIndex: Int) {
        clearBackStack()
        currentDestination.value = when (tabIndex) {
            0 -> NavigationDestination.Home
            1 -> NavigationDestination.Transactions
            2 -> NavigationDestination.Review
            3 -> NavigationDestination.Budget
            4 -> NavigationDestination.Analytics
            5 -> NavigationDestination.SpendingMap
            else -> NavigationDestination.Home
        }
        _navigationEvents.tryEmit(NavigationEvent.NavigateTo(currentDestination.value))
    }
    
    /**
     * Check if currently on a specific destination.
     */
    fun isCurrent(destination: NavigationDestination): Boolean {
        return currentDestination.value == destination
    }
    
    /**
     * Check if currently on any of the main tabs.
     */
    fun isOnMainTab(): Boolean {
        return isMainTab(currentDestination.value)
    }
    
    /**
     * Check if destination is a main tab (0-5).
     */
    private fun isMainTab(destination: NavigationDestination): Boolean {
        return when (destination) {
            is NavigationDestination.Home,
            is NavigationDestination.Transactions,
            is NavigationDestination.Review,
            is NavigationDestination.Budget,
            is NavigationDestination.Analytics,
            is NavigationDestination.SpendingMap -> true
            else -> false
        }
    }
    
    /**
     * Get the current tab index (0-5) if on a main tab, null otherwise.
     */
    fun getCurrentTabIndex(): Int? {
        return when (currentDestination.value) {
            is NavigationDestination.Home -> 0
            is NavigationDestination.Transactions -> 1
            is NavigationDestination.Review -> 2
            is NavigationDestination.Budget -> 3
            is NavigationDestination.Analytics -> 4
            is NavigationDestination.SpendingMap -> 5
            else -> null
        }
    }
    
    /**
     * Check if there's anything in the back stack.
     */
    fun canNavigateBack(): Boolean {
        return backStack.isNotEmpty() || !isOnMainTab()
    }
    
    /**
     * Clear the back stack (e.g., when switching tabs).
     */
    fun clearBackStack() {
        backStack.clear()
    }
}

/**
 * Navigation events that can be observed for analytics, logging, etc.
 */
sealed class NavigationEvent {
    data class NavigateTo(val destination: NavigationDestination) : NavigationEvent()
    data object NavigateBack : NavigationEvent()
}

/**
 * CompositionLocal to provide NavigationController throughout the composition.
 * 
 * Usage:
 * ```kotlin
 * val navigation = LocalNavigationController.current
 * Button(onClick = { navigation.navigateTo(NavigationDestination.AddExpense) }) {
 *     Text("Add Expense")
 * }
 * ```
 */
val LocalNavigationController = compositionLocalOf<NavigationController> {
    error("No NavigationController provided. Ensure NavigationController is provided at the root of your composition.")
}

/**
 * Provides a NavigationController to its content.
 * 
 * @param initialDestination The starting destination, defaults to Home
 * @param content The composable content that will have access to the NavigationController
 */
@Composable
fun ProvideNavigationController(
    initialDestination: NavigationDestination = NavigationDestination.Home,
    content: @Composable () -> Unit
) {
    val currentDestination = remember { mutableStateOf(initialDestination) }
    val navigationController = remember { NavigationController(currentDestination) }
    
    CompositionLocalProvider(
        LocalNavigationController provides navigationController
    ) {
        content()
    }
}

/**
 * Helper function to consume navigation events.
 * Use this to observe navigation events for analytics, logging, etc.
 * 
 * @param onEvent Callback for each navigation event
 */
@Composable
fun ConsumeNavigationEvents(
    onEvent: (NavigationEvent) -> Unit
) {
    val navigation = LocalNavigationController.current
    
    DisposableEffect(navigation) {
        // Note: In a real implementation, you'd collect from navigation.navigationEvents
        // For now, this is a placeholder for future analytics integration
        onDispose { }
    }
}
