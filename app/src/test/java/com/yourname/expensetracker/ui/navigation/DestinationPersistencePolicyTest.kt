package com.yourname.expensetracker.ui.navigation

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract test for destination persistence policies.
 *
 * Ensures that degraded/ephemeral destinations are explicitly documented
 * and that their serialization behavior matches their policy.
 */
class DestinationPersistencePolicyTest {

    @Test
    fun `degraded destinations serialize but lose payload`() {
        val degraded = listOf(
            NavigationDestination.BudgetForecasting(),
            NavigationDestination.VisualSplitEditor()
        )

        degraded.forEach { dest ->
            assertEquals(
                "${dest::class.simpleName} should be DEGRADED",
                DestinationPersistencePolicy.DEGRADED,
                dest.persistencePolicy()
            )
            // They still serialize (token exists)
            val token = dest.toSaveToken()
            assertTrue("${dest::class.simpleName} should have a token", token.isNotBlank())
            // They restore (but without payload)
            assertNotNull("${dest::class.simpleName} should restore from token", destinationFromSaveToken(token))
        }
    }

    @Test
    fun `ephemeral destinations are documented`() {
        val ephemeral = listOf(
            NavigationDestination.AddExpense,
            NavigationDestination.ScanReceipt,
            NavigationDestination.Assistant,
            NavigationDestination.Debug
        )

        ephemeral.forEach { dest ->
            assertEquals(
                "${dest::class.simpleName} should be EPHEMERAL",
                DestinationPersistencePolicy.EPHEMERAL,
                dest.persistencePolicy()
            )
        }
    }

    @Test
    fun `main tabs are FULL persistence`() {
        val mainTabs = listOf(
            NavigationDestination.Home,
            NavigationDestination.Transactions(),
            NavigationDestination.Review,
            NavigationDestination.Budget,
            NavigationDestination.Analytics(),
            NavigationDestination.SpendingMap()
        )

        mainTabs.forEach { dest ->
            assertEquals(
                "${dest::class.simpleName} should be FULL",
                DestinationPersistencePolicy.FULL,
                dest.persistencePolicy()
            )
        }
    }

    @Test
    fun `feature destinations default to FULL persistence`() {
        val features = listOf(
            NavigationDestination.SavingsGoals,
            NavigationDestination.WarrantyTracker,
            NavigationDestination.InvestmentPortfolio,
            NavigationDestination.BackupRestore,
            NavigationDestination.SharedExpenseGroups
        )

        features.forEach { dest ->
            assertEquals(
                "${dest::class.simpleName} should be FULL",
                DestinationPersistencePolicy.FULL,
                dest.persistencePolicy()
            )
        }
    }
}
