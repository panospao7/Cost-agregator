package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MerchantCategoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantCategoryDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.merchantCategoryDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun getCategoryByNormalizedCanonical_duplicateCandidates_higherConfidenceWins() = runBlocking {
        // Insert two categories first (needed for FK)
        val cat1 = Category(id = 0, name = "Food", icon = "🍔", color = "#E53935")
        val cat2 = Category(id = 0, name = "Transport", icon = "🚌", color = "#1E88E5")
        val catDao = database.categoryDao()
        val catId1 = catDao.insert(cat1)
        val catId2 = catDao.insert(cat2)

        // Insert two mappings with same normalizedCanonicalName but different confidence
        dao.insert(MerchantCategory(
            merchantPattern = "low_conf",
            categoryId = catId1,
            confidence = 0.5f,
            timesUsed = 10,
            normalizedCanonicalName = "mcdonalds"
        ))
        dao.insert(MerchantCategory(
            merchantPattern = "high_conf",
            categoryId = catId2,
            confidence = 0.9f,
            timesUsed = 1,
            normalizedCanonicalName = "mcdonalds"
        ))

        val result = dao.getCategoryByNormalizedCanonical("mcdonalds")
        assertNotNull(result)
        assertEquals(catId2, result!!.categoryId) // higher confidence wins
    }

    @Test
    fun getCategoryByNormalizedCanonical_sameConfidence_higherTimesUsedWins() = runBlocking {
        val cat1 = Category(id = 0, name = "Food", icon = "🍔", color = "#E53935")
        val cat2 = Category(id = 0, name = "Transport", icon = "🚌", color = "#1E88E5")
        val catDao = database.categoryDao()
        val catId1 = catDao.insert(cat1)
        val catId2 = catDao.insert(cat2)

        // Same confidence, different timesUsed
        dao.insert(MerchantCategory(
            merchantPattern = "low_use",
            categoryId = catId1,
            confidence = 0.8f,
            timesUsed = 3,
            normalizedCanonicalName = "starbucks"
        ))
        dao.insert(MerchantCategory(
            merchantPattern = "high_use",
            categoryId = catId2,
            confidence = 0.8f,
            timesUsed = 15,
            normalizedCanonicalName = "starbucks"
        ))

        val result = dao.getCategoryByNormalizedCanonical("starbucks")
        assertNotNull(result)
        assertEquals(catId2, result!!.categoryId) // higher timesUsed wins
    }

    @Test
    fun getCategoryByNormalizedCanonical_sameConfidenceAndTimesUsed_lexicalTieBreaks() = runBlocking {
        val cat1 = Category(id = 0, name = "Food", icon = "🍔", color = "#E53935")
        val cat2 = Category(id = 0, name = "Transport", icon = "🚌", color = "#1E88E5")
        val catDao = database.categoryDao()
        val catId1 = catDao.insert(cat1)
        val catId2 = catDao.insert(cat2)

        // Same confidence, same timesUsed — lexical tie-breaker by merchantPattern ASC
        dao.insert(MerchantCategory(
            merchantPattern = "z_pattern",
            categoryId = catId2,
            confidence = 0.7f,
            timesUsed = 5,
            normalizedCanonicalName = "lidl"
        ))
        dao.insert(MerchantCategory(
            merchantPattern = "a_pattern",
            categoryId = catId1,
            confidence = 0.7f,
            timesUsed = 5,
            normalizedCanonicalName = "lidl"
        ))

        val result = dao.getCategoryByNormalizedCanonical("lidl")
        assertNotNull(result)
        assertEquals(catId1, result!!.categoryId) // "a_pattern" < "z_pattern" alphabetically
    }

    @Test
    fun getCategoryByNormalizedCanonical_noMatch_returnsNull() = runBlocking {
        val result = dao.getCategoryByNormalizedCanonical("nonexistent")
        assertEquals(null, result)
    }
}
