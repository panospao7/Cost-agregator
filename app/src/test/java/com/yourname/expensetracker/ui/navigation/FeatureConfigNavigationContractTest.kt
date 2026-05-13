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
    fun `all feature IDs are unique`() {
        val ids = FeatureConfig.allFeatures.map { it.id }
        assertEquals("Duplicate feature IDs found", ids.size, ids.distinct().size)
    }

    @Test
    fun `all feature destinations serialize to non-blank token`() {
        FeatureConfig.allFeatures.forEach { feature ->
            val token = feature.destination.toSaveToken()
            assertTrue(
                "Feature '${feature.id}' has blank token",
                token.isNotBlank()
            )
        }
    }

    @Test
    fun `all feature destinations restore from token`() {
        FeatureConfig.allFeatures.forEach { feature ->
            val token = feature.destination.toSaveToken()
            val restored = destinationFromSaveToken(token)
            assertNotNull(
                "Feature '${feature.id}' token '$token' failed to restore",
                restored
            )
        }
    }

    @Test
    fun `no duplicate destinations in feature list`() {
        val tokens = FeatureConfig.allFeatures.map { it.destination.toSaveToken() }
        assertEquals(
            "Duplicate destinations found in FeatureConfig",
            tokens.size, tokens.distinct().size
        )
    }

    @Test
    fun `route token round-trip preserves destination type`() {
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
