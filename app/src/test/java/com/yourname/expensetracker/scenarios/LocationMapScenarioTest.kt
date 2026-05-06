package com.yourname.expensetracker.scenarios

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scenario tests for merchant location seeding and querying.
 *
 * These tests verify that [MerchantLocation] entities can be inserted,
 * looked up by normalized name (global and area-scoped), and that
 * the upsert behaviour correctly increments hit counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationMapScenarioTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private val now = 1_714_514_400_000L // 2024-05-01T00:00:00Z

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Merchant location inserted and queryable globally
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `merchant location inserted and queryable globally`() = runTest {
        // GIVEN: a merchant location for SKLAVENITIS
        val location = MerchantLocation(
            normalizedMerchantName = "sklavenitis",
            areaKey = "global",
            displayName = "SKLAVENITIS",
            latitude = 37.9838,
            longitude = 23.7275,
            source = "NOMINATIM_GPS_BIAS",
            osmId = "node/12345",
            displayAddress = "Σκλαβενίτης, Αθήνα",
            confidence = 0.95f,
            lastResolvedAt = now,
            hitCount = 1
        )

        // WHEN: inserting via upsert
        db.merchantLocationDao().upsertLocation(location)

        // THEN: the location exists with correct fields
        val saved = db.merchantLocationDao().getGlobalByNormalizedName("sklavenitis")
        assertNotNull("Global merchant location should exist", saved)
        assertEquals("displayName should match", "SKLAVENITIS", saved!!.displayName)
        assertEquals("latitude should match", 37.9838, saved.latitude, 0.0001)
        assertEquals("longitude should match", 23.7275, saved.longitude, 0.0001)
        assertEquals("source should match", "NOMINATIM_GPS_BIAS", saved.source)
        assertEquals("displayAddress should match", "Σκλαβενίτης, Αθήνα", saved.displayAddress)
        assertEquals("confidence should match", 0.95f, saved.confidence, 0.0f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Area-scoped merchant location lookup
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `area-scoped merchant location lookup works`() = runTest {
        // GIVEN: two merchant locations for the same normalized name but different areas
        val locationGlobal = MerchantLocation(
            normalizedMerchantName = "sklavenitis",
            areaKey = "global",
            displayName = "SKLAVENITIS",
            latitude = 37.9838,
            longitude = 23.7275,
            source = "NOMINATIM_NAME_ONLY",
            lastResolvedAt = now,
            hitCount = 1
        )

        val locationGlyfada = MerchantLocation(
            normalizedMerchantName = "sklavenitis",
            areaKey = "sklavenitis|100|200",
            displayName = "SKLAVENITIS ΓΛΥΦΑΔΑ",
            latitude = 37.8800,
            longitude = 23.7500,
            source = "OVERPASS_POI",
            osmId = "way/67890",
            displayAddress = "Σκλαβενίτης, Γλυφάδα",
            confidence = 0.98f,
            lastResolvedAt = now,
            hitCount = 1
        )

        // WHEN: inserting both
        db.merchantLocationDao().upsertLocation(locationGlobal)
        db.merchantLocationDao().upsertLocation(locationGlyfada)

        // THEN: global lookup returns the global entry
        val global = db.merchantLocationDao().getGlobalByNormalizedName("sklavenitis")
        assertNotNull("Global entry should exist", global)
        assertEquals("Global entry should have NOMINATIM_NAME_ONLY source",
            "NOMINATIM_NAME_ONLY", global!!.source)

        // AND: area-scoped lookup returns the area-specific entry
        val area = db.merchantLocationDao().getByNormalizedNameAndArea(
            "sklavenitis", "sklavenitis|100|200"
        )
        assertNotNull("Area-scoped entry should exist", area)
        assertEquals("Area entry should have GLYFADA display name",
            "SKLAVENITIS ΓΛΥΦΑΔΑ", area!!.displayName)
        assertEquals("Area entry should have OVERPASS_POI source",
            "OVERPASS_POI", area.source)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Merchant location upsert increments hit count
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `merchant location upsert increments hit count`() = runTest {
        // GIVEN: a first merchant location insertion
        val location = MerchantLocation(
            normalizedMerchantName = "amazon",
            areaKey = "global",
            displayName = "Amazon",
            latitude = 47.6062,
            longitude = -122.3321,
            source = "NOMINATIM_GPS_BIAS",
            displayAddress = "Amazon HQ, Seattle",
            confidence = 0.90f,
            lastResolvedAt = now,
            hitCount = 1
        )

        // WHEN: inserting the same location twice (upsert)
        db.merchantLocationDao().upsertLocation(location)

        // Insert again — upsert should increment hitCount
        val locationRepeat = location.copy(
            lastResolvedAt = now + 3600_000L,
            hitCount = 1 // The DAO will ignore this; it adds 1 to existing.hitCount
        )
        db.merchantLocationDao().upsertLocation(locationRepeat)

        // THEN: only one row exists
        val count = db.merchantLocationDao().count()
        assertEquals("Should have exactly 1 location row", 1, count)

        // AND: the hitCount should be 2 (original 1 + 1 from upsert)
        val saved = db.merchantLocationDao().getGlobalByNormalizedName("amazon")
        assertNotNull("Location should exist", saved)
        assertEquals("hitCount should be 2 after second upsert", 2, saved!!.hitCount)

        // AND: the most recent data (lat, lon, source) are updated
        assertEquals("confidence should be preserved", 0.90f, saved.confidence, 0.0f)
        assertTrue("lastResolvedAt should be updated",
            saved.lastResolvedAt >= now + 3600_000L)
    }
}
