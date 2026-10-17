package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredFeatureGateTest {

    @Test
    fun `deferred feature ids are exactly tax investment challenges`() {
        assertEquals(setOf("tax", "investment", "challenges"), DeferredFeatureGate.DEFERRED_FEATURE_IDS)
    }

    @Test
    fun `availability follows the debug build flag`() {
        assertEquals(BuildConfig.DEBUG, DeferredFeatureGate.available)
    }

    @Test
    fun `deferred save tokens match the destination tokens`() {
        val tokens = setOf(
            NavigationDestination.TaxConfiguration.toSaveToken(),
            NavigationDestination.InvestmentPortfolio.toSaveToken(),
            NavigationDestination.SpendingChallenges().toSaveToken().substringBefore('?')
        )
        assertEquals(DeferredFeatureGate.DEFERRED_SAVE_TOKENS, tokens)
    }

    @Test
    fun `deferred destinations redirect home when unavailable`() {
        listOf(
            NavigationDestination.TaxConfiguration,
            NavigationDestination.InvestmentPortfolio,
            NavigationDestination.SpendingChallenges(),
            NavigationDestination.SpendingChallenges(showCreateDialog = true)
        ).forEach {
            assertTrue(DeferredFeatureGate.shouldRedirectHome(it, deferredAvailable = false))
            assertFalse(DeferredFeatureGate.shouldRedirectHome(it, deferredAvailable = true))
        }
    }

    @Test
    fun `non-deferred destinations never redirect`() {
        listOf(
            NavigationDestination.Home,
            NavigationDestination.SavingsGoals,
            NavigationDestination.BillReminders,
            NavigationDestination.PrivacySettings
        ).forEach {
            assertFalse(DeferredFeatureGate.shouldRedirectHome(it, deferredAvailable = false))
        }
    }

    @Test
    fun `deferred features map to deferred destinations`() {
        DeferredFeatureGate.DEFERRED_FEATURE_IDS.forEach { id ->
            val feature = FeatureConfig.getById(id)!!
            assertTrue(DeferredFeatureGate.isDeferredDestination(feature.destination))
        }
    }
}
