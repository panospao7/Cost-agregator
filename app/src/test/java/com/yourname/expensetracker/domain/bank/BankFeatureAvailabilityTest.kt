package com.yourname.expensetracker.domain.bank

import com.yourname.expensetracker.ui.navigation.NavigationDestination
import com.yourname.expensetracker.ui.navigation.destinationFromSaveToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RP-17 17-A: central availability policy (register D12 / D1).
 *
 * The bank-sync surface is unavailable in release builds; debug/provider mode
 * is permitted. Bank deep-link hosts are recognized and rejected — there is
 * intentionally no bank deep-link route, and adding one must consciously pass
 * this gate.
 */
class BankFeatureAvailabilityTest {

    @Test
    fun `release build reports bank sync unavailable with typed reason`() {
        val availability = BankFeatureAvailability.forDebugMode(debugMode = false)

        assertEquals(false, availability.isBankSyncAvailable)
        val result = availability.availability()
        assertTrue(result is BankAvailability.FeatureUnavailable)
        assertEquals(
            BankUnavailableReason.RELEASE_BUILD,
            (result as BankAvailability.FeatureUnavailable).reason
        )
    }

    @Test
    fun `debug provider mode permits bank sync per D1`() {
        val availability = BankFeatureAvailability.forDebugMode(debugMode = true)

        assertEquals(true, availability.isBankSyncAvailable)
        assertEquals(BankAvailability.Available, availability.availability())
    }

    @Test
    fun `bank deep link hosts are recognized`() {
        for (host in listOf("bank", "banks", "bank_connections")) {
            assertTrue("host $host must be recognized as a bank host",
                BankFeatureAvailability.isBankDeepLinkHost(host))
            // Case-insensitive on the host component.
            assertTrue(BankFeatureAvailability.isBankDeepLinkHost(host.uppercase()))
        }
    }

    @Test
    fun `non-bank and null hosts are not bank hosts`() {
        assertEquals(false, BankFeatureAvailability.isBankDeepLinkHost("home"))
        assertEquals(false, BankFeatureAvailability.isBankDeepLinkHost("activity"))
        assertEquals(false, BankFeatureAvailability.isBankDeepLinkHost("review"))
        assertEquals(false, BankFeatureAvailability.isBankDeepLinkHost(null))
    }

    // ── 17-A: persisted destination restoration gate ─────────────────────────

    @Test
    fun `bank_connections token restores when surface is available`() {
        assertEquals(
            NavigationDestination.BankConnections,
            destinationFromSaveToken("bank_connections", bankSyncAvailable = true)
        )
    }

    @Test
    fun `bank_connections token is replaced at restore when surface is unavailable`() {
        // Unavailable builds return null → callers fall back to Home / filter the
        // back stack instead of rendering or entering the feature.
        assertNull(destinationFromSaveToken("bank_connections", bankSyncAvailable = false))
    }

    @Test
    fun `restore gate does not affect non-bank destinations`() {
        assertEquals(
            NavigationDestination.Home,
            destinationFromSaveToken("home", bankSyncAvailable = false)
        )
        assertEquals(
            NavigationDestination.Review,
            destinationFromSaveToken("review", bankSyncAvailable = false)
        )
    }
}
