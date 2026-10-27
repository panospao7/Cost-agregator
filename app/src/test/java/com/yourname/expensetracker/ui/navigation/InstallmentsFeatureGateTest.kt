package com.yourname.expensetracker.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10a-1: the dormant-feature gate (menu entry + render redirect).
 */
class InstallmentsFeatureGateTest {

    private val installmentDestinations: List<NavigationDestination> = listOf(
        NavigationDestination.Installments,
        NavigationDestination.InstallmentPlanDetail(7L),
        NavigationDestination.InstallmentHistory
    )

    private val otherDestinations: List<NavigationDestination> = listOf(
        NavigationDestination.Home,
        NavigationDestination.Review,
        NavigationDestination.AddExpense,
        NavigationDestination.Budget,
        NavigationDestination.ManualRecurringExpense,
        NavigationDestination.Transactions()
    )

    // ---- FeatureConfig menu gate ----

    @Test
    fun `installments is absent from availableFeatures by default while the gate is false`() {
        // Canary: S13 flips InstallmentsFeature.ENABLED and must update this test.
        assertFalse(InstallmentsFeature.ENABLED)
        listOf(true, false).forEach { bankSync ->
            val ids = FeatureConfig.availableFeatures(bankSyncAvailable = bankSync).map { it.id }
            assertFalse("bankSync=$bankSync", InstallmentsFeature.FEATURE_ID in ids)
        }
    }

    @Test
    fun `installments is absent from availableFeatures when explicitly disabled`() {
        listOf(true, false).forEach { deferred ->
            val ids = FeatureConfig.availableFeatures(
                bankSyncAvailable = true,
                deferredAvailable = deferred,
                installmentsEnabled = false
            ).map { it.id }
            assertFalse("deferred=$deferred", "installments" in ids)
        }
    }

    @Test
    fun `installments is present in availableFeatures when enabled`() {
        listOf(true, false).forEach { deferred ->
            val features = FeatureConfig.availableFeatures(
                bankSyncAvailable = true,
                deferredAvailable = deferred,
                installmentsEnabled = true
            )
            val entry = features.singleOrNull { it.id == "installments" }
            assertNotNull("deferred=$deferred", entry)
            assertEquals(NavigationDestination.Installments, entry!!.destination)
        }
    }

    @Test
    fun `installments stays in allFeatures regardless of the flag`() {
        val matches = FeatureConfig.allFeatures.filter { it.id == "installments" }
        assertEquals(1, matches.size)
        assertEquals(NavigationDestination.Installments, matches.single().destination)
        assertNotNull(FeatureConfig.getById("installments"))
    }

    @Test
    fun `enabling installments does not add or drop any other feature`() {
        val withFlag = FeatureConfig.availableFeatures(
            bankSyncAvailable = true,
            installmentsEnabled = true
        ).map { it.id }
        val withoutFlag = FeatureConfig.availableFeatures(
            bankSyncAvailable = true,
            installmentsEnabled = false
        ).map { it.id }

        assertEquals(withoutFlag, withFlag.filterNot { it == "installments" })
        assertEquals(withoutFlag.size + 1, withFlag.size)
    }

    // ---- shouldRedirectHome truth table ----

    @Test
    fun `shouldRedirectHome is true for every installment destination when disabled`() {
        installmentDestinations.forEach { destination ->
            assertTrue(
                "$destination must redirect Home while disabled",
                InstallmentsFeature.shouldRedirectHome(destination, enabled = false)
            )
        }
    }

    @Test
    fun `shouldRedirectHome is false for every installment destination when enabled`() {
        installmentDestinations.forEach { destination ->
            assertFalse(
                "$destination must render when enabled",
                InstallmentsFeature.shouldRedirectHome(destination, enabled = true)
            )
        }
    }

    @Test
    fun `shouldRedirectHome is false for non-installment destinations in both flag states`() {
        otherDestinations.forEach { destination ->
            listOf(false, true).forEach { enabled ->
                assertFalse(
                    "$destination enabled=$enabled",
                    InstallmentsFeature.shouldRedirectHome(destination, enabled = enabled)
                )
            }
        }
    }

    @Test
    fun `shouldRedirectHome defaults to the compile-time gate`() {
        (installmentDestinations + otherDestinations).forEach { destination ->
            assertEquals(
                InstallmentsFeature.shouldRedirectHome(destination, enabled = InstallmentsFeature.ENABLED),
                InstallmentsFeature.shouldRedirectHome(destination)
            )
        }
        // Canary: while dormant, the default must redirect an installment destination.
        assertFalse(InstallmentsFeature.ENABLED)
        assertTrue(InstallmentsFeature.shouldRedirectHome(NavigationDestination.Installments))
    }

    @Test
    fun `isInstallmentDestination recognises exactly the three installment destinations`() {
        installmentDestinations.forEach { assertTrue("$it", InstallmentsFeature.isInstallmentDestination(it)) }
        otherDestinations.forEach { assertFalse("$it", InstallmentsFeature.isInstallmentDestination(it)) }
    }
}
