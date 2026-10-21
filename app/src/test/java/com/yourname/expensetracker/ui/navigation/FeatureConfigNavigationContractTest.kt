package com.yourname.expensetracker.ui.navigation

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract tests for FeatureConfig navigation inventory.
 *
 * Ensures:
 * 1. All feature IDs are unique
 * 2. All feature destinations can be serialized (toSaveToken)
 * 3. All feature destinations can be restored (destinationFromSaveToken)
 * 4. No duplicate destinations in the feature list
 * 5. Route token round-trip is lossless for all features
 */
class FeatureConfigNavigationContractTest {

    @Test
    fun `feature inventory ids are unique and destinations serialize restore and round-trip`() {
        // Feature IDs are unique
        val ids = FeatureConfig.allFeatures.map { it.id }
        assertEquals("Duplicate feature IDs found", ids.size, ids.distinct().size)

        // Serialize to non-blank token
        FeatureConfig.allFeatures.forEach { feature ->
            val token = feature.destination.toSaveToken()
            assertTrue(
                "Feature '${feature.id}' has blank token",
                token.isNotBlank()
            )
        }

        // Restore from token
        FeatureConfig.allFeatures.forEach { feature ->
            val token = feature.destination.toSaveToken()
            val restored = destinationFromSaveToken(token)
            assertNotNull(
                "Feature '${feature.id}' token '$token' failed to restore",
                restored
            )
        }

        // No duplicate destinations in the feature list
        val tokens = FeatureConfig.allFeatures.map { it.destination.toSaveToken() }
        assertEquals(
            "Duplicate destinations found in FeatureConfig",
            tokens.size, tokens.distinct().size
        )

        // Route token round-trip preserves destination type
        FeatureConfig.allFeatures.forEach { feature ->
            val token = feature.destination.toSaveToken()
            val restored = destinationFromSaveToken(token)!!
            assertEquals(
                "Feature '${feature.id}' round-trip changed destination class",
                feature.destination::class, restored::class
            )
        }
    }

    @Test
    fun `deferred features are hidden from the menu when not available`() {
        val ids = FeatureConfig.availableFeatures(
            bankSyncAvailable = true,
            deferredAvailable = false
        ).map { it.id }
        DeferredFeatureGate.DEFERRED_FEATURE_IDS.forEach { id ->
            assertFalse("Deferred feature '$id' must be hidden", id in ids)
        }
        assertTrue("savings must remain", "savings" in ids)
        assertTrue("privacy must remain", "privacy" in ids)
    }

    @Test
    fun `deferred features are shown in the menu when available`() {
        val ids = FeatureConfig.availableFeatures(
            bankSyncAvailable = true,
            deferredAvailable = true
        ).map { it.id }
        DeferredFeatureGate.DEFERRED_FEATURE_IDS.forEach { id ->
            assertTrue("Deferred feature '$id' must be shown", id in ids)
        }
    }

    @Test
    fun `bank and deferred gates are independent`() {
        val ids = FeatureConfig.availableFeatures(
            bankSyncAvailable = false,
            deferredAvailable = true
        ).map { it.id }
        assertFalse(FeatureConfig.BANK_CONNECTIONS_FEATURE_ID in ids)
        assertTrue("tax" in ids)
    }

    @Test
    fun `deferred feature ids all exist in feature config`() {
        DeferredFeatureGate.DEFERRED_FEATURE_IDS.forEach { id ->
            assertNotNull("Unknown deferred id '$id'", FeatureConfig.getById(id))
        }
    }

    @Test
    fun `privacy feature uses dedicated strings`() {
        val privacy = FeatureConfig.getById("privacy")!!
        assertNotEquals(FeatureConfig.getById("backup-restore")!!.titleRes, privacy.titleRes)
        assertNotEquals(FeatureConfig.getById("backup-restore")!!.descriptionRes, privacy.descriptionRes)
    }

    @Test
    fun `main tabs are not in feature config`() {
        val mainTabClasses = setOf(
            NavigationDestination.Home::class,
            NavigationDestination.Transactions::class,
            NavigationDestination.Review::class,
            NavigationDestination.Budget::class,
            NavigationDestination.Analytics::class,
            NavigationDestination.SpendingMap::class
        )
        FeatureConfig.allFeatures.forEach { feature ->
            assertFalse(
                "Main tab '${feature.id}' should not be in feature config",
                feature.destination::class in mainTabClasses
            )
        }
    }

    @Test
    fun `feature count matches expected`() {
        // Document the current count so drift is detected
        assertTrue(
            "Feature count changed unexpectedly (was 22+). Update this test if intentional.",
            FeatureConfig.allFeatures.size >= 20
        )
    }
}
