package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredFeatureGateTest {

    @Test
    fun `deferred feature ids are tax investment challenges carbon price negotiation`() {
        // Extended in S4: carbon, price, and negotiation were gated from release because they render fabricated financial data.
        assertEquals(
            setOf("tax", "investment", "challenges", "carbon", "price", "negotiation"),
            DeferredFeatureGate.DEFERRED_FEATURE_IDS
        )
    }

    @Test
    fun `availability follows the debug build flag`() {
        assertEquals(BuildConfig.DEBUG, DeferredFeatureGate.available)
    }

    @Test
    fun `deferred save tokens match the destination tokens`() {
        // Extended in S4: carbon_footprint, price_protection, and bill_negotiation were gated from release because they render fabricated financial data.
        val tokens = setOf(
            NavigationDestination.TaxConfiguration.toSaveToken(),
            NavigationDestination.InvestmentPortfolio.toSaveToken(),
            NavigationDestination.SpendingChallenges().toSaveToken().substringBefore('?'),
            NavigationDestination.CarbonFootprint.toSaveToken(),
            NavigationDestination.PriceProtection.toSaveToken(),
            NavigationDestination.BillNegotiation.toSaveToken()
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

    @Test
    fun `fabricated data features carbon price negotiation and existing deferred set are gated by id`() {
        listOf("carbon", "price", "negotiation", "tax", "investment", "challenges").forEach { id ->
            assertTrue("Expected '$id' to be deferred", DeferredFeatureGate.isDeferredFeatureId(id))
        }
        listOf("savings", "warranty", "bill-reminders", "privacy").forEach { id ->
            assertFalse("Expected '$id' not to be deferred", DeferredFeatureGate.isDeferredFeatureId(id))
        }
    }

    @Test
    fun `carbon price negotiation destinations are deferred and redirect home when unavailable`() {
        listOf(
            NavigationDestination.CarbonFootprint,
            NavigationDestination.PriceProtection,
            NavigationDestination.BillNegotiation,
            NavigationDestination.TaxConfiguration,
            NavigationDestination.InvestmentPortfolio,
            NavigationDestination.SpendingChallenges()
        ).forEach { destination ->
            assertTrue(
                "Expected $destination to be a deferred destination",
                DeferredFeatureGate.isDeferredDestination(destination)
            )
            assertTrue(
                "Expected $destination to redirect home when deferredAvailable=false",
                DeferredFeatureGate.shouldRedirectHome(destination, deferredAvailable = false)
            )
            assertFalse(
                "Expected $destination not to redirect home when deferredAvailable=true",
                DeferredFeatureGate.shouldRedirectHome(destination, deferredAvailable = true)
            )
        }
    }
}
