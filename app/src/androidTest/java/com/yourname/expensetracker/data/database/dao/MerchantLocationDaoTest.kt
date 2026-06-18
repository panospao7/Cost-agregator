package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MerchantLocation
import com.yourname.expensetracker.data.database.entity.MerchantLocationCorrection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantLocationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantLocationDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.merchantLocationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeLocation(
        normalizedName: String,
        areaKey: String = "global",
        displayName: String = normalizedName,
        latitude: Double = 37.97,
        longitude: Double = 23.73,
        source: String = "NOMINATIM_NAME_ONLY",
        hitCount: Int = 1,
        lastResolvedAt: Long = System.currentTimeMillis()
    ) = MerchantLocation(
        normalizedMerchantName = normalizedName,
        areaKey = areaKey,
        displayName = displayName,
        latitude = latitude,
        longitude = longitude,
        source = source,
        hitCount = hitCount,
        lastResolvedAt = lastResolvedAt
    )

    // ── areaKey is non-null by default ──────────────────────────────────────

    @Test
    fun areaKey_defaults_to_global_when_not_specified() = runBlocking {
        val loc = MerchantLocation(
            normalizedMerchantName = "starbucks",
            displayName = "Starbucks",
            latitude = 37.97,
            longitude = 23.73,
            source = "NOMINATIM_NAME_ONLY"
        )
        assertEquals("global", loc.areaKey)

        dao.insertLocation(loc)

        val loaded = dao.getGlobalByNormalizedName("starbucks")
        assertNotNull(loaded)
        assertEquals("global", loaded!!.areaKey)
    }

    // ── getGlobalByNormalizedName strict global lookup ──────────────────────

    @Test
    fun getGlobalByNormalizedName_returns_global_entry_when_both_global_and_area_exist() = runBlocking {
        val globalLoc = makeLocation("lidl", areaKey = "global", hitCount = 1)
        val areaLoc = makeLocation("lidl", areaKey = "lidl|843|527", hitCount = 5)
        dao.insertLocation(globalLoc)
        dao.insertLocation(areaLoc)

        val result = dao.getGlobalByNormalizedName("lidl")
        assertNotNull(result)
        assertEquals("global", result!!.areaKey)
    }

    @Test
    fun getGlobalByNormalizedName_returns_null_when_no_global_exists() = runBlocking {
        val areaLoc = makeLocation("lidl", areaKey = "lidl|843|527")
        dao.insertLocation(areaLoc)

        val result = dao.getGlobalByNormalizedName("lidl")
        assertNull(result)
    }

    @Test
    fun getGlobalByNormalizedName_returns_null_for_unknown_merchant() = runBlocking {
        assertNull(dao.getGlobalByNormalizedName("nonexistent"))
    }

    // ── getByNormalizedNameAndArea exact match ──────────────────────────────

    @Test
    fun getByNormalizedNameAndArea_returns_exact_match() = runBlocking {
        dao.insertLocation(makeLocation("ab", areaKey = "ab|100|200"))
        dao.insertLocation(makeLocation("ab", areaKey = "global"))

        val result = dao.getByNormalizedNameAndArea("ab", "ab|100|200")
        assertNotNull(result)
        assertEquals("ab|100|200", result!!.areaKey)
    }

    // ── Unique constraint on (normalizedMerchantName, areaKey) ─────────────

    @Test
    fun duplicate_normalizedName_and_areaKey_violates_unique_constraint() = runBlocking {
        dao.insertLocation(makeLocation("abc", areaKey = "global"))
        try {
            dao.insertLocation(makeLocation("abc", areaKey = "global"))
            fail("Expected SQLiteConstraintException for duplicate (normalizedMerchantName, areaKey)")
        } catch (_: SQLiteConstraintException) {
            // expected
        }
    }

    @Test
    fun same_normalizedName_different_areaKey_allowed() = runBlocking {
        dao.insertLocation(makeLocation("abc", areaKey = "global"))
        dao.insertLocation(makeLocation("abc", areaKey = "abc|1|2"))

        assertEquals(2, dao.count())
    }

    // ── upsertLocation ─────────────────────────────────────────────────────

    @Test
    fun upsertLocation_inserts_new_entry() = runBlocking {
        val loc = makeLocation("metro", areaKey = "global", displayName = "Metro Cash & Carry")
        dao.upsertLocation(loc)

        val loaded = dao.getByNormalizedNameAndArea("metro", "global")
        assertNotNull(loaded)
        assertEquals("Metro Cash & Carry", loaded!!.displayName)
        assertEquals(1, loaded.hitCount)
    }

    @Test
    fun upsertLocation_updates_existing_and_increments_hitCount() = runBlocking {
        val v1 = makeLocation("metro", areaKey = "global", displayName = "Metro v1", latitude = 37.0)
        dao.upsertLocation(v1)

        val v2 = makeLocation("metro", areaKey = "global", displayName = "Metro v2", latitude = 38.0)
        dao.upsertLocation(v2)

        val loaded = dao.getByNormalizedNameAndArea("metro", "global")
        assertNotNull(loaded)
        assertEquals("Metro v2", loaded!!.displayName)
        assertEquals(38.0, loaded.latitude, 0.001)
        assertEquals(2, loaded.hitCount)
    }

    // ── incrementHitCount only matches areaKey='global' ────────────────────

    @Test
    fun incrementHitCount_only_affects_global_entries() = runBlocking {
        dao.insertLocation(makeLocation("sklavenitis", areaKey = "global", hitCount = 1))
        dao.insertLocation(makeLocation("sklavenitis", areaKey = "sklavenitis|843|527", hitCount = 1))

        dao.incrementHitCount("sklavenitis")

        val global = dao.getByNormalizedNameAndArea("sklavenitis", "global")
        val area = dao.getByNormalizedNameAndArea("sklavenitis", "sklavenitis|843|527")

        assertEquals(2, global!!.hitCount)
        assertEquals(1, area!!.hitCount)
    }

    @Test
    fun incrementHitCount_no_op_when_no_global_entry() = runBlocking {
        dao.insertLocation(makeLocation("sklavenitis", areaKey = "sklavenitis|843|527", hitCount = 3))

        dao.incrementHitCount("sklavenitis")

        val area = dao.getByNormalizedNameAndArea("sklavenitis", "sklavenitis|843|527")
        assertEquals(3, area!!.hitCount)
    }

    // ── incrementHitCountForArea ────────────────────────────────────────────

    @Test
    fun incrementHitCountForArea_targets_specific_area() = runBlocking {
        dao.insertLocation(makeLocation("ab", areaKey = "ab|1|2", hitCount = 5))
        dao.insertLocation(makeLocation("ab", areaKey = "global", hitCount = 10))

        dao.incrementHitCountForArea("ab", "ab|1|2")

        assertEquals(6, dao.getByNormalizedNameAndArea("ab", "ab|1|2")!!.hitCount)
        assertEquals(10, dao.getByNormalizedNameAndArea("ab", "global")!!.hitCount)
    }

    // ── deleteStaleEntries ──────────────────────────────────────────────────

    @Test
    fun deleteStaleEntries_removes_old_entries_preserves_recent() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertLocation(makeLocation("old", lastResolvedAt = now - 100_000))
        dao.insertLocation(makeLocation("new", lastResolvedAt = now))

        dao.deleteStaleEntries(now - 50_000)

        assertEquals(1, dao.count())
        assertNotNull(dao.getGlobalByNormalizedName("new"))
        assertNull(dao.getGlobalByNormalizedName("old"))
    }

    // ── Global correction → merchant_locations cache coherence ─────────────

    @Test
    fun global_correction_uses_canonical_global_areaKey_in_cache() = runBlocking {
        // A global correction has null area coords → areaKey must be plain "global"
        val correction = MerchantLocationCorrection(
            normalizedMerchantName = "starbucks",
            correctedLatitude = 37.98,
            correctedLongitude = 23.72,
            areaLatitude = null,
            areaLongitude = null
        )

        // Verify buildAreaKey produces the canonical "global" (not "starbucks|global")
        assertEquals("global", correction.areaKey)

        // Persist the correction
        dao.upsertCorrection(correction)

        // Simulate what MerchantLocationRepository.saveCorrection() does:
        // mirror the correction into the merchant_locations cache using correction.areaKey
        dao.upsertLocation(
            MerchantLocation(
                normalizedMerchantName = correction.normalizedMerchantName,
                areaKey = correction.areaKey,
                displayName = "Starbucks",
                latitude = correction.correctedLatitude,
                longitude = correction.correctedLongitude,
                source = "USER_MANUAL",
                confidence = 1.0f,
                lastResolvedAt = System.currentTimeMillis()
            )
        )

        // The cached row must be findable via the standard global lookup path
        val cached = dao.getGlobalByNormalizedName("starbucks")
        assertNotNull(cached)
        assertEquals("global", cached!!.areaKey)
        assertEquals(37.98, cached.latitude, 0.001)
        assertEquals(23.72, cached.longitude, 0.001)

        // Also reachable via the explicit area-scoped lookup with "global"
        val byArea = dao.getByNormalizedNameAndArea("starbucks", "global")
        assertNotNull(byArea)
        assertEquals("global", byArea!!.areaKey)

        // Verify the correction itself round-trips correctly
        val loadedCorrection = dao.getLatestCorrection("starbucks")
        assertNotNull(loadedCorrection)
        assertEquals("global", loadedCorrection!!.areaKey)
    }
}
