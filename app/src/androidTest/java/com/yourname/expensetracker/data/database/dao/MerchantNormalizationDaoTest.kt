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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantNormalizationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantNormalizationDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
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
}
