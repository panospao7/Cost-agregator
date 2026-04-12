package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantNormalizationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantNormalizationDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.merchantNormalizationDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeCanonical(
        name: String,
        searchKey: String,
        now: Long = System.currentTimeMillis()
    ) = MerchantCanonical(
        normalizedName = name,
        searchKey = searchKey,
        createdAt = now,
        updatedAt = now
    )

    private fun makeAlias(
        rawName: String,
        normalizedKey: String,
        canonicalId: Long,
        createdAt: Long = System.currentTimeMillis(),
        lastUsedAt: Long = createdAt
    ) = MerchantAlias(
        rawName = rawName,
        normalizedKey = normalizedKey,
        canonicalId = canonicalId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    @Test
    fun insertMerchantMapping_andRetrieveByRawName() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val alias = makeAlias("STARBUCKS #123", "starbucks123", canonicalId)

        dao.insertAlias(alias)

        val loaded = dao.getAliasByRawName("STARBUCKS #123")
        assertNotNull(loaded)
        assertEquals("starbucks123", loaded!!.normalizedKey)
        assertEquals(canonicalId, loaded.canonicalId)
    }

    @Test
    fun upsertMapping_updatesExistingAlias() = runBlocking {
        val canonicalA = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val canonicalB = dao.insertCanonical(makeCanonical("Starbucks Coffee", "starbuckscoffee"))
        val firstTs = 1_700_000_000_000L
        val secondTs = firstTs + 1_000L

        dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalA,
            isUserDefined = false,
            timestamp = firstTs
        )
        dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalB,
            isUserDefined = true,
            timestamp = secondTs
        )

        val loaded = dao.getAliasByRawName("STARBUCKS #123")
        assertNotNull(loaded)
        assertEquals(canonicalB, loaded!!.canonicalId)
        assertEquals(2, loaded.occurrenceCount)
        assertEquals(true, loaded.isUserDefined)
        assertEquals(secondTs, loaded.lastUsedAt)
    }

    @Test
    fun queryByNormalizedKey_returnsExpectedMapping() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Sklavenitis", "sklavenitis"))
        dao.insertAlias(makeAlias("ΣΚΛΑΒΕΝΙΤΗΣ", "sklavenitis", canonicalId))

        val loaded = dao.getAliasByNormalizedKey("sklavenitis")
        assertNotNull(loaded)
        assertEquals("ΣΚΛΑΒΕΝΙΤΗΣ", loaded!!.rawName)
    }

    @Test
    fun deleteMapping_verifiesRemoved() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Lidl", "lidl"))
        val oldTs = 1_650_000_000_000L
        dao.insertAlias(
            makeAlias(
                rawName = "LIDL ATHENS",
                normalizedKey = "lidlathens",
                canonicalId = canonicalId,
                createdAt = oldTs,
                lastUsedAt = oldTs
            )
        )

        val deleted = dao.deleteUnusedAliasesOlderThan(oldTs + 1)

        assertEquals(1, deleted)
        assertNull(dao.getAliasByRawName("LIDL ATHENS"))
    }

    // ── Batch 5: searchKey uniqueness ──────────────────────────────────────

    @Test
    fun insertCanonical_duplicate_searchKey_returns_minus_one() = runBlocking {
        val first = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        assertTrue(first > 0)

        // IGNORE strategy → returns -1 on conflict
        val second = dao.insertCanonical(makeCanonical("Starbucks Coffee", "starbucks"))
        assertEquals(-1L, second)

        // Only one row exists
        assertEquals(1, dao.getCanonicalCount())
    }

    @Test
    fun insertCanonical_different_searchKeys_both_succeed() = runBlocking {
        val a = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val b = dao.insertCanonical(makeCanonical("Costa Coffee", "costacoffee"))

        assertTrue(a > 0)
        assertTrue(b > 0)
        assertTrue(a != b)
        assertEquals(2, dao.getCanonicalCount())
    }

    // ── Batch 5: normalizedKey uniqueness ──────────────────────────────────

    @Test
    fun insertAlias_duplicate_normalizedKey_returns_minus_one() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Lidl", "lidl"))

        val first = dao.insertAlias(makeAlias("LIDL A", "lidl_norm", canonicalId))
        assertTrue(first > 0)

        // Different rawName but same normalizedKey → unique conflict
        val second = dao.insertAlias(makeAlias("LIDL B", "lidl_norm", canonicalId))
        assertEquals(-1L, second)
    }

    @Test
    fun insertAlias_different_normalizedKeys_both_succeed() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Lidl", "lidl"))

        val a = dao.insertAlias(makeAlias("LIDL A", "lidla", canonicalId))
        val b = dao.insertAlias(makeAlias("LIDL B", "lidlb", canonicalId))

        assertTrue(a > 0)
        assertTrue(b > 0)
        assertTrue(a != b)
    }

    // ── Batch 5: deterministic getCanonicalBySearchKey (ORDER BY id DESC) ──

    @Test
    fun getCanonicalBySearchKey_returns_row_for_unique_searchKey() = runBlocking {
        dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))

        val result = dao.getCanonicalBySearchKey("starbucks")
        assertNotNull(result)
        assertEquals("Starbucks", result!!.normalizedName)
    }

    @Test
    fun getCanonicalBySearchKey_returns_null_for_missing_key() = runBlocking {
        assertNull(dao.getCanonicalBySearchKey("nonexistent"))
    }

    // ── Batch 5: deterministic getAliasByNormalizedKey (ORDER BY id DESC) ──

    @Test
    fun getAliasByNormalizedKey_returns_row_for_unique_normalizedKey() = runBlocking {
        val canonicalId = dao.insertCanonical(makeCanonical("Sklavenitis", "sklavenitis"))
        dao.insertAlias(makeAlias("ΣΚΛΑΒΕΝΙΤΗΣ", "sklavenitis_norm", canonicalId))

        val loaded = dao.getAliasByNormalizedKey("sklavenitis_norm")
        assertNotNull(loaded)
        assertEquals("ΣΚΛΑΒΕΝΙΤΗΣ", loaded!!.rawName)
    }

    @Test
    fun getAliasByNormalizedKey_returns_null_for_missing_key() = runBlocking {
        assertNull(dao.getAliasByNormalizedKey("nonexistent"))
    }
}
