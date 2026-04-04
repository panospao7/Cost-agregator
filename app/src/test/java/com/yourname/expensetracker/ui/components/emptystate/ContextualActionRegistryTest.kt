package com.yourname.expensetracker.ui.components.emptystate

import app.cash.turbine.test
import androidx.compose.ui.graphics.vector.ImageVector
import com.yourname.expensetracker.ui.navigation.NavigationDestination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.mockk

class ContextualActionRegistryTest {

    private lateinit var registry: ContextualActionRegistry

    @Before
    fun setup() {
        registry = ContextualActionRegistry()
    }

    @Test
    fun `completedActions emits when markCompleted is called`() = runTest {
        registry.completedActions.test {
            assertEquals(emptySet<String>(), awaitItem())

            registry.markCompleted("budget", "create_budget")
            assertEquals(setOf("budget:create_budget"), awaitItem())

            registry.markCompleted("budget", "review_limits")
            assertEquals(
                setOf("budget:create_budget", "budget:review_limits"),
                awaitItem()
            )
        }
    }

    @Test
    fun `registerActions stores actions sorted by descending priority`() {
        val low = action(id = "low", priority = 1)
        val high = action(id = "high", priority = 10)
        val medium = action(id = "medium", priority = 5)

        registry.registerActions("savings", listOf(low, high, medium))

        val result = registry.getActions("savings", excludeCompleted = false)
        assertEquals(listOf("high", "medium", "low"), result.map { it.id })
    }

    @Test
    fun `markCompleted and getActions track completion per screen key`() {
        val a1 = action(id = "a1", priority = 2)
        val a2 = action(id = "a2", priority = 1)

        registry.registerActions("screenA", listOf(a1, a2))
        registry.registerActions("screenB", listOf(a1, a2))

        registry.markCompleted("screenA", "a1")

        assertTrue(registry.isCompleted("screenA", "a1"))
        assertFalse(registry.isCompleted("screenB", "a1"))

        assertEquals(listOf("a2"), registry.getActions("screenA").map { it.id })
        assertEquals(listOf("a1", "a2"), registry.getActions("screenB").map { it.id })
    }

    @Test
    fun `clearCompleted removes completed state only for given screen`() {
        val a1 = action(id = "a1")
        val a2 = action(id = "a2")

        registry.registerActions("one", listOf(a1, a2))
        registry.registerActions("two", listOf(a1, a2))
        registry.markCompleted("one", "a1")
        registry.markCompleted("two", "a2")

        registry.clearCompleted("one")

        assertFalse(registry.isCompleted("one", "a1"))
        assertTrue(registry.isCompleted("two", "a2"))
        assertEquals(listOf("a1", "a2"), registry.getActions("one").map { it.id })
        assertEquals(listOf("a1"), registry.getActions("two").map { it.id })
    }

    @Test
    fun `clearAll resets registrations and completions`() {
        registry.registerActions("analytics", listOf(action(id = "x")))
        registry.markCompleted("analytics", "x")

        registry.clearAll()

        assertFalse(registry.hasActions("analytics"))
        assertEquals(emptyList<EmptyStateAction>(), registry.getActions("analytics"))
        assertEquals(0, registry.getRemainingActionCount("analytics"))
        assertEquals(emptySet<String>(), registry.completedActions.value)
    }

    @Test
    fun `duplicate action ids are all filtered once id is completed`() {
        val duplicateLow = action(id = "duplicate", priority = 1)
        val duplicateHigh = action(id = "duplicate", priority = 9)

        registry.registerActions("receipts", listOf(duplicateLow, duplicateHigh))
        assertEquals(2, registry.getActions("receipts").size)

        registry.markCompleted("receipts", "duplicate")

        assertEquals(emptyList<EmptyStateAction>(), registry.getActions("receipts"))
        assertEquals(0, registry.getRemainingActionCount("receipts"))
    }

    @Test
    fun `unknown screen key returns empty defaults and remains safe on clear`() {
        assertFalse(registry.hasActions("unknown"))
        assertEquals(emptyList<EmptyStateAction>(), registry.getActions("unknown"))
        assertEquals(0, registry.getRemainingActionCount("unknown"))
        assertFalse(registry.isCompleted("unknown", "anything"))

        registry.clearCompleted("unknown")

        assertEquals(emptySet<String>(), registry.completedActions.value)
    }

    private fun action(id: String, priority: Int = 0): EmptyStateAction {
        return EmptyStateAction(
            id = id,
            title = "Title $id",
            description = "Description $id",
            icon = mockk<ImageVector>(relaxed = true),
            action = EmptyStateActionType.NavigateToDestination(NavigationDestination.Home),
            priority = priority
        )
    }
}
