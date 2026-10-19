package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MerchantNormalizationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantNormalizationDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.merchantNormalizationDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private fun makeCanonical(
        name: String,
        searchKey: String,
        now: Long = 1_700_000_000_000L
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
        createdAt: Long = 1_700_000_000_000L,
        lastUsedAt: Long = createdAt
    ) = MerchantAlias(
        rawName = rawName,
        normalizedKey = normalizedKey,
        canonicalId = canonicalId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt
    )

    @Test
    fun insertMerchantMapping_andRetrieveByRawName() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val alias = makeAlias("STARBUCKS #123", "starbucks123", canonicalId)

        dao.insertAlias(alias)

        val loaded = dao.getAliasByRawName("STARBUCKS #123")
        assertNotNull(loaded)
        assertEquals("starbucks123", loaded!!.normalizedKey)
        assertEquals(canonicalId, loaded.canonicalId)
    }

    @Test
    fun upsertMapping_updatesExistingAlias() = runTest {
        val canonicalA = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val firstTs = 1_700_000_000_000L
        val secondTs = firstTs + 1_000L

        val firstResult = dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalA,
            isUserDefined = false,
            timestamp = firstTs
        )
        assertEquals(0, firstResult) // CREATED

        val secondResult = dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalA,
            isUserDefined = true,
            timestamp = secondTs
        )
        assertEquals(1, secondResult) // UPDATED

        val loaded = dao.getAliasByRawName("STARBUCKS #123")
        assertNotNull(loaded)
        assertEquals(canonicalA, loaded!!.canonicalId)
        assertEquals(2, loaded.occurrenceCount)
        assertEquals(true, loaded.isUserDefined)
        assertEquals(secondTs, loaded.lastUsedAt)
    }

    @Test
    fun linkAliasToCanonical_differentCanonical_returnsConflict() = runTest {
        val canonicalA = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val canonicalB = dao.insertCanonical(makeCanonical("Starbucks Coffee", "starbuckscoffee"))
        val ts = 1_700_000_000_000L

        val firstResult = dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalA,
            timestamp = ts
        )
        assertEquals(0, firstResult) // CREATED

        val secondResult = dao.linkAliasToCanonical(
            rawName = "STARBUCKS #123",
            normalizedKey = "starbucks123",
            canonicalId = canonicalB,
            timestamp = ts + 1_000L
        )
        assertEquals(2, secondResult) // CONFLICT

        // Alias should still point to canonicalA
        val loaded = dao.getAliasByRawName("STARBUCKS #123")
        assertNotNull(loaded)
        assertEquals(canonicalA, loaded!!.canonicalId)
        assertEquals(1, loaded.occurrenceCount)
    }

    @Test
    fun linkAliasToCanonical_sameNormalizedKeyDifferentRawName_updatesExisting() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("McDonald's", "mcdonalds"))
        val firstTs = 1_700_000_000_000L
        val secondTs = firstTs + 1_000L

        val firstResult = dao.linkAliasToCanonical(
            rawName = "McDonald's",
            normalizedKey = "mcdonalds",
            canonicalId = canonicalId,
            timestamp = firstTs
        )
        assertEquals(0, firstResult) // CREATED

        val secondResult = dao.linkAliasToCanonical(
            rawName = "MCDONALDS",
            normalizedKey = "mcdonalds",
            canonicalId = canonicalId,
            timestamp = secondTs
        )
        assertEquals(1, secondResult) // UPDATED

        val loaded = dao.getAliasByNormalizedKey("mcdonalds")
        assertNotNull(loaded)
        assertEquals(2, loaded!!.occurrenceCount)
        assertEquals(secondTs, loaded.lastUsedAt)
    }

    @Test
    fun queryByNormalizedKey_returnsExpectedMapping() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("Sklavenitis", "sklavenitis"))
        dao.insertAlias(makeAlias("ΣΚΛΑΒΕΝΙΤΗΣ", "sklavenitis", canonicalId))

        val loaded = dao.getAliasByNormalizedKey("sklavenitis")
        assertNotNull(loaded)
        assertEquals("ΣΚΛΑΒΕΝΙΤΗΣ", loaded!!.rawName)
    }

    @Test
    fun deleteMapping_verifiesRemoved() = runTest {
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
    fun insertCanonical_duplicate_searchKey_returns_minus_one() = runTest {
        val first = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        assertTrue(first > 0)

        // IGNORE strategy → returns -1 on conflict
        val second = dao.insertCanonical(makeCanonical("Starbucks Coffee", "starbucks"))
        assertEquals(-1L, second)

        // Only one row exists
        assertEquals(1, dao.getCanonicalCount())
    }

    @Test
    fun insertCanonical_different_searchKeys_both_succeed() = runTest {
        val a = dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))
        val b = dao.insertCanonical(makeCanonical("Costa Coffee", "costacoffee"))

        assertTrue(a > 0)
        assertTrue(b > 0)
        assertTrue(a != b)
        assertEquals(2, dao.getCanonicalCount())
    }

    // ── Batch 5: normalizedKey uniqueness ──────────────────────────────────

    @Test
    fun insertAlias_duplicate_normalizedKey_returns_minus_one() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("Lidl", "lidl"))

        val first = dao.insertAlias(makeAlias("LIDL A", "lidl_norm", canonicalId))
        assertTrue(first > 0)

        // Different rawName but same normalizedKey → unique conflict
        val second = dao.insertAlias(makeAlias("LIDL B", "lidl_norm", canonicalId))
        assertEquals(-1L, second)
    }

    @Test
    fun insertAlias_different_normalizedKeys_both_succeed() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("Lidl", "lidl"))

        val a = dao.insertAlias(makeAlias("LIDL A", "lidla", canonicalId))
        val b = dao.insertAlias(makeAlias("LIDL B", "lidlb", canonicalId))

        assertTrue(a > 0)
        assertTrue(b > 0)
        assertTrue(a != b)
    }

    // ── Batch 5: deterministic getCanonicalBySearchKey (ORDER BY id DESC) ──

    @Test
    fun getCanonicalBySearchKey_returns_row_for_unique_searchKey() = runTest {
        dao.insertCanonical(makeCanonical("Starbucks", "starbucks"))

        val result = dao.getCanonicalBySearchKey("starbucks")
        assertNotNull(result)
        assertEquals("Starbucks", result!!.normalizedName)
    }

    @Test
    fun getCanonicalBySearchKey_returns_null_for_missing_key() = runTest {
        assertNull(dao.getCanonicalBySearchKey("nonexistent"))
    }

    // ── Batch 5: deterministic getAliasByNormalizedKey (ORDER BY id DESC) ──

    @Test
    fun getAliasByNormalizedKey_returns_row_for_unique_normalizedKey() = runTest {
        val canonicalId = dao.insertCanonical(makeCanonical("Sklavenitis", "sklavenitis"))
        dao.insertAlias(makeAlias("ΣΚΛΑΒΕΝΙΤΗΣ", "sklavenitis_norm", canonicalId))

        val loaded = dao.getAliasByNormalizedKey("sklavenitis_norm")
        assertNotNull(loaded)
        assertEquals("ΣΚΛΑΒΕΝΙΤΗΣ", loaded!!.rawName)
    }

    @Test
    fun getAliasByNormalizedKey_returns_null_for_missing_key() = runTest {
        assertNull(dao.getAliasByNormalizedKey("nonexistent"))
    }

    // ── E3-PR1: CANONICAL_MISSING path ──────────────────────────────────────

    @Test
    fun linkAliasToCanonical_missingCanonical_returnsCanonicalMissing() = runTest {
        val result = dao.linkAliasToCanonical(
            rawName = "Unknown Merchant",
            normalizedKey = "unknownmerchant",
            canonicalId = 99999L,
            timestamp = 1_700_000_000_000L
        )
        assertEquals(3, result) // CANONICAL_MISSING
    }
}
