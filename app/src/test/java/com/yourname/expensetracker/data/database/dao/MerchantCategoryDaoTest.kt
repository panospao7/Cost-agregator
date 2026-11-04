package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MerchantCategoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantCategoryDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.merchantCategoryDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun getCategoryByNormalizedCanonical_duplicateCandidates_higherConfidenceWins() = runTest {
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
    fun getCategoryByNormalizedCanonical_sameConfidence_higherTimesUsedWins() = runTest {
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
    fun getCategoryByNormalizedCanonical_sameConfidenceAndTimesUsed_lexicalTieBreaks() = runTest {
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

    // ---- DR-04: user correction replaces; seeding and auto-learning never overwrite ----

    private suspend fun twoCategories(): Pair<Long, Long> {
        val groceries = database.categoryDao().insert(Category(id = 0, name = "Groceries", icon = "G", color = "#43A047"))
        val dining = database.categoryDao().insert(Category(id = 0, name = "Dining", icon = "D", color = "#E53935"))
        return groceries to dining
    }

    @Suppress("DEPRECATION")
    @Test
    fun DR04_upsertUserCorrection_replacesSeededRow_andDictionaryReturnsCorrection() = runTest {
        val (groceries, dining) = twoCategories()
        dao.insert(MerchantCategory(merchantPattern = "lidl", categoryId = groceries, normalizedCanonicalName = "lidl"))

        dao.upsertUserCorrection(MerchantCategory(merchantPattern = "lidl", categoryId = dining, normalizedCanonicalName = "lidl"))

        assertEquals(dining, dao.getCategoryForMerchant("lidl")!!.categoryId)
        assertEquals(dining, dao.getCategoryByNormalizedCanonical("lidl")!!.categoryId)
        assertEquals(1, dao.getCount())
    }

    @Suppress("DEPRECATION")
    @Test
    fun DR04_nonVacuity_autoLearnInsertOnSameKey_leavesSeededRow() = runTest {
        val (groceries, dining) = twoCategories()
        dao.insert(MerchantCategory(merchantPattern = "lidl", categoryId = groceries, normalizedCanonicalName = "lidl"))

        val ignored = dao.insert(MerchantCategory(merchantPattern = "lidl", categoryId = dining, normalizedCanonicalName = "lidl"))

        assertEquals(-1L, ignored)
        assertEquals(groceries, dao.getCategoryForMerchant("lidl")!!.categoryId)
    }

    @Suppress("DEPRECATION")
    @Test
    fun DR04_correctionThenInsertOrInsertAll_ofOtherCategory_leavesCorrection() = runTest {
        val (groceries, dining) = twoCategories()
        dao.upsertUserCorrection(MerchantCategory(merchantPattern = "lidl", categoryId = dining, normalizedCanonicalName = "lidl"))

        dao.insert(MerchantCategory(merchantPattern = "lidl", categoryId = groceries, normalizedCanonicalName = "lidl"))
        dao.insertAll(listOf(MerchantCategory(merchantPattern = "lidl", categoryId = groceries, normalizedCanonicalName = "lidl")))

        assertEquals(dining, dao.getCategoryForMerchant("lidl")!!.categoryId)
        assertEquals(1, dao.getCount())
    }

    @Test
    fun DR04_upsertUserCorrection_noExistingRow_insertsAndLeavesOtherKeysUntouched() = runTest {
        val (groceries, dining) = twoCategories()
        dao.upsertUserCorrection(MerchantCategory(merchantPattern = "sklavenitis", categoryId = groceries, normalizedCanonicalName = "sklavenitis"))
        dao.upsertUserCorrection(MerchantCategory(merchantPattern = "lidl", categoryId = dining, normalizedCanonicalName = "lidl"))

        assertEquals(groceries, dao.getCategoryForMerchant("sklavenitis")!!.categoryId)
        assertEquals(dining, dao.getCategoryForMerchant("lidl")!!.categoryId)
        assertEquals(2, dao.getCount())
    }

    @Test
    fun getCategoryByNormalizedCanonical_noMatch_returnsNull() = runTest {
        val result = dao.getCategoryByNormalizedCanonical("nonexistent")
        assertEquals(null, result)
    }
}
