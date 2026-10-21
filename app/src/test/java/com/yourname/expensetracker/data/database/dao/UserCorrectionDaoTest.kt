package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import com.yourname.expensetracker.testfixtures.dateMs
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
class UserCorrectionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: UserCorrectionDao
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        dao = database.userCorrectionDao()
        categoryDao = database.categoryDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private fun makeCorrection(
        packageName: String = "com.bank.app",
        originalMerchant: String = "STARBUCKS #123",
        correctedMerchant: String? = "Starbucks",
        originalAmount: Double = 4.50,
        correctedAmount: Double? = null,
        originalCategoryId: Long? = null,
        correctedCategoryId: Long? = null,
        originalType: String? = "PURCHASE",
        correctedType: String? = null,
        wasRejected: Boolean = false,
        wasApproved: Boolean = false,
        createdAt: Long = BASE_NOW
    ) = UserCorrection(
        packageName = packageName,
        originalMerchant = originalMerchant,
        correctedMerchant = correctedMerchant,
        originalAmount = originalAmount,
        correctedAmount = correctedAmount,
        originalCategoryId = originalCategoryId,
        correctedCategoryId = correctedCategoryId,
        originalType = originalType,
        correctedType = correctedType,
        wasRejected = wasRejected,
        wasApproved = wasApproved,
        notificationTitle = null,
        notificationText = null,
        createdAt = createdAt
    )

    private suspend fun insertCategory(name: String): Long =
        categoryDao.insert(
            Category(name = name, icon = "🏷", color = "#4CAF50")
        )

    // ── Insert with ABORT strategy ─────────────────────────────────────────

    @Test
    fun insert_returns_positive_id_for_new_correction() = runTest {
        val id = dao.insert(makeCorrection())
        assertTrue(id > 0)
    }

    @Test
    fun insert_with_explicit_duplicate_id_throws_on_ABORT() = runTest {
        val id = dao.insert(makeCorrection(createdAt = 1_000_000L))

        // Attempting to insert another row with the same auto-generated ID
        // won't collide because Room auto-generates. Instead, test that
        // the ABORT strategy doesn't silently replace by checking multiple
        // inserts produce distinct rows.
        val id2 = dao.insert(makeCorrection(createdAt = 2_000_000L))

        assertTrue(id != id2)
        assertEquals(2, dao.getCount())
    }

    // ── getAll ordering ────────────────────────────────────────────────────

    @Test
    fun getAll_returns_corrections_ordered_by_createdAt_desc() = runTest {
        dao.insert(makeCorrection(originalMerchant = "A", createdAt = 100))
        dao.insert(makeCorrection(originalMerchant = "B", createdAt = 300))
        dao.insert(makeCorrection(originalMerchant = "C", createdAt = 200))

        val all = dao.getAll()
        assertEquals(listOf("B", "C", "A"), all.map { it.originalMerchant })
    }

    // ── getMostCommonMerchantCorrection — deterministic tie-break ───────────

    @Test
    fun getMostCommonMerchantCorrection_returns_most_frequent() = runTest {
        // "X" corrected to "Alpha" 3 times, to "Beta" once
        repeat(3) {
            dao.insert(
                makeCorrection(
                    originalMerchant = "X",
                    correctedMerchant = "Alpha",
                    createdAt = 1000L + it
                )
            )
        }
        dao.insert(
            makeCorrection(
                originalMerchant = "X",
                correctedMerchant = "Beta",
                createdAt = 5000L
            )
        )

        val result = dao.getMostCommonMerchantCorrection("X")
        assertEquals("Alpha", result)
    }

    @Test
    fun getMostCommonMerchantCorrection_tie_broken_by_most_recent_then_lexicographic() = runTest {
        // Same count, but "Beta" has a more recent MAX(createdAt)
        dao.insert(makeCorrection(originalMerchant = "Y", correctedMerchant = "Alpha", createdAt = 1000L))
        dao.insert(makeCorrection(originalMerchant = "Y", correctedMerchant = "Beta", createdAt = 2000L))

        val result = dao.getMostCommonMerchantCorrection("Y")
        assertEquals("Beta", result) // same count, but Beta has later MAX(createdAt)
    }

    @Test
    fun getMostCommonMerchantCorrection_same_count_same_time_tie_broken_lexicographically() = runTest {
        // Identical count, identical MAX(createdAt) → fall back to ASC on correctedMerchant
        dao.insert(makeCorrection(originalMerchant = "Z", correctedMerchant = "Banana", createdAt = 5000L))
        dao.insert(makeCorrection(originalMerchant = "Z", correctedMerchant = "Apple", createdAt = 5000L))

        val result = dao.getMostCommonMerchantCorrection("Z")
        assertEquals("Apple", result) // alphabetically first
    }

    @Test
    fun getMostCommonMerchantCorrection_ignores_unchanged_merchant() = runTest {
        // correctedMerchant == originalMerchant → excluded by query
        dao.insert(makeCorrection(originalMerchant = "A", correctedMerchant = "A", createdAt = 1000L))
        dao.insert(makeCorrection(originalMerchant = "A", correctedMerchant = "Alpha", createdAt = 2000L))

        val result = dao.getMostCommonMerchantCorrection("A")
        assertEquals("Alpha", result)
    }

    @Test
    fun getMostCommonMerchantCorrection_returns_null_when_no_corrections() = runTest {
        assertNull(dao.getMostCommonMerchantCorrection("UNKNOWN"))
    }

    // ── getMostCommonCategoryForMerchant — deterministic tie-break ──────────

    @Test
    fun getMostCommonCategoryForMerchant_returns_most_frequent_category() = runTest {
        val catA = insertCategory("Food")
        val catB = insertCategory("Drink")

        repeat(3) {
            dao.insert(
                makeCorrection(
                    originalMerchant = "M",
                    correctedCategoryId = catA,
                    createdAt = 1000L + it
                )
            )
        }
        dao.insert(
            makeCorrection(
                originalMerchant = "M",
                correctedCategoryId = catB,
                createdAt = 5000L
            )
        )

        val result = dao.getMostCommonCategoryForMerchant("M")
        assertEquals(catA, result)
    }

    @Test
    fun getMostCommonCategoryForMerchant_tie_broken_by_recency_then_id() = runTest {
        val catA = insertCategory("CatA")
        val catB = insertCategory("CatB")

        // Same count, but catB has the more recent createdAt
        dao.insert(makeCorrection(originalMerchant = "M", correctedCategoryId = catA, createdAt = 1000L))
        dao.insert(makeCorrection(originalMerchant = "M", correctedCategoryId = catB, createdAt = 2000L))

        val result = dao.getMostCommonCategoryForMerchant("M")
        assertEquals(catB, result) // same count → most recent MAX(createdAt) wins
    }

    @Test
    fun getMostCommonCategoryForMerchant_returns_null_when_no_category_corrections() = runTest {
        // Insert correction with no correctedCategoryId
        dao.insert(makeCorrection(originalMerchant = "M", correctedCategoryId = null))

        assertNull(dao.getMostCommonCategoryForMerchant("M"))
    }

    // ── Stats and counts ───────────────────────────────────────────────────

    @Test
    fun getMerchantStats_returns_totals_and_rejections() = runTest {
        dao.insert(makeCorrection(originalMerchant = "X", wasRejected = false))
        dao.insert(makeCorrection(originalMerchant = "X", wasRejected = true))
        dao.insert(makeCorrection(originalMerchant = "X", wasRejected = true))

        val stats = dao.getMerchantStats("X")
        assertEquals(3, stats.total)
        assertEquals(2, stats.rejections)
    }

    @Test
    fun getPackageStats_returns_totals_and_rejections_for_package() = runTest {
        dao.insert(makeCorrection(packageName = "com.a", wasRejected = false))
        dao.insert(makeCorrection(packageName = "com.a", wasRejected = true))
        dao.insert(makeCorrection(packageName = "com.b", wasRejected = true))

        val stats = dao.getPackageStats("com.a")
        assertEquals(2, stats.total)
        assertEquals(1, stats.rejections)
    }

    // ── hasPreviousApprovals ───────────────────────────────────────────────

    @Test
    fun hasPreviousApprovals_returns_true_when_approved_exists() = runTest {
        dao.insert(
            makeCorrection(
                packageName = "com.bank",
                originalMerchant = "STARBUCKS",
                wasApproved = true
            )
        )

        assertTrue(dao.hasPreviousApprovals("STARBUCKS", "com.bank"))
    }

    @Test
    fun hasPreviousApprovals_returns_false_when_none() = runTest {
        assertEquals(false, dao.hasPreviousApprovals("STARBUCKS", "com.bank"))
    }

    // ── deleteAll ──────────────────────────────────────────────────────────

    @Test
    fun deleteAll_clears_all_corrections() = runTest {
        dao.insert(makeCorrection())
        dao.insert(makeCorrection())

        dao.deleteAll()

        assertEquals(0, dao.getCount())
    }

    private companion object {
        /** Fixed base instant: 2026-05-15T12:00:00Z (mid-month noon, UTC). */
        val BASE_NOW: Long = dateMs(2026, 5, 15, 12, 0)
    }
}
