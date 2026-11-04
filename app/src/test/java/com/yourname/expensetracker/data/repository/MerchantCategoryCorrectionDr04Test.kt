package com.yourname.expensetracker.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DR-04: a user's category correction replaces the stored merchant mapping; auto-learning and
 * seeding never overwrite anything. Real in-memory Room, real DAO, real repository, real engine
 * and real normalizer; only the clock, the category list and the semantic/context engines are mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MerchantCategoryCorrectionDr04Test {

    private val groceries = 1L
    private val dining = 2L
    private val transport = 3L
    private val rawLidl = "LIDL #123 ATHENS"

    private lateinit var database: AppDatabase
    private lateinit var dao: MerchantCategoryDao
    private lateinit var repository: MerchantCategoryRepository
    private lateinit var engine: CategorizationEngine
    private lateinit var normalizer: MerchantNormalizer
    private var mode = RestoreMaintenanceMode.Mode.NORMAL

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = AppDatabaseTestFactory.create(context)
        dao = database.merchantCategoryDao()

        val maintenance = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { maintenance.currentMode() } answers { mode }
        every { maintenance.isWritesAllowed() } answers { mode == RestoreMaintenanceMode.Mode.NORMAL }
        val barrier = DatabaseWriteBarrier(maintenance)

        val timeProvider = mockk<TimeProvider>()
        every { timeProvider.now() } returns 1_700_000_000_000L

        val categories = listOf(
            Category(id = groceries, name = "Groceries", icon = "G", color = "#43A047"),
            Category(id = dining, name = "Dining", icon = "D", color = "#E53935"),
            Category(id = transport, name = "Transport", icon = "T", color = "#1E88E5")
        )
        runBlocking { categories.forEach { database.categoryDao().insert(it) } }
        val categoryRepository = mockk<CategoryRepository>()
        coEvery { categoryRepository.getAll() } returns categories

        repository = MerchantCategoryRepository(
            writeBarrier = barrier,
            dao = dao,
            categorizationEngineProvider = javax.inject.Provider { engine }
        )
        normalizer = MerchantNormalizer(
            repository = MerchantNormalizationRepository(
                writeBarrier = barrier,
                dao = database.merchantNormalizationDao(),
                timeProvider = timeProvider
            ),
            merchantRules = MerchantRulesRepository(),
            greeklishNormalizer = GreeklishNormalizer(),
            context = context,
            timeProvider = timeProvider
        )
        engine = CategorizationEngine(
            merchantCategoryRepository = repository,
            merchantNormalizer = normalizer,
            categoryRepositoryProvider = javax.inject.Provider { categoryRepository },
            canonicalizer = MerchantCanonicalizer(),
            greeklishNormalizer = GreeklishNormalizer(),
            semanticMatcher = mockk(relaxed = true),
            contextEngine = mockk(relaxed = true),
            timeProvider = timeProvider
        )
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    /** Seeds Groceries for the Lidl key through the legal insert path (IGNORE semantics). */
    private fun seedLidlGroceries() = runBlocking {
        val inserted = repository.insert(engine.createMerchantCategoryMapping(rawLidl, groceries))
        assertTrue(inserted is MerchantCategoryInsertResult.Inserted)
    }

    private fun classify(raw: String): Long? = runBlocking { engine.categorize(raw).categoryId }

    @Test
    fun DR04_seededGroceries_userCorrectsToDining_dictionaryAndLaterClassificationReturnDining() {
        seedLidlGroceries()
        assertEquals("precondition: seed is read", groceries, classify(rawLidl))

        runBlocking { repository.correctPattern(rawLidl, dining) }

        assertEquals(dining, runBlocking { repository.getCategoryForMerchant(rawLidl) }!!.categoryId)
        assertEquals("a later Lidl row", dining, classify(rawLidl))
        assertEquals("one row, replaced", 1, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_nonVacuity_autoLearnOnSameRow_leavesGroceries() {
        seedLidlGroceries()
        assertEquals(groceries, classify(rawLidl))

        runBlocking { repository.learnPattern(rawLidl, dining) }

        assertEquals(groceries, runBlocking { repository.getCategoryForMerchant(rawLidl) }!!.categoryId)
        assertEquals(groceries, classify(rawLidl))
        assertEquals(1, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_correctionThenAutoLearnOrSeedOfGroceries_leavesDining() {
        seedLidlGroceries()
        runBlocking { repository.correctPattern(rawLidl, dining) }

        runBlocking {
            repository.learnPattern(rawLidl, groceries)
            val seedAgain = repository.insert(engine.createMerchantCategoryMapping(rawLidl, groceries))
            assertTrue("seed is ignored", seedAgain is MerchantCategoryInsertResult.Conflict)
        }

        assertEquals(dining, runBlocking { repository.getCategoryForMerchant(rawLidl) }!!.categoryId)
        assertEquals(dining, classify(rawLidl))
        assertEquals(1, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_correctionLandsOnTheNormalizedKeyTheClassifierReads() {
        runBlocking { repository.correctPattern(rawLidl, dining) }

        // The key the classifier derives (MerchantNormalizer, autoCreate = false, compared ignoring case).
        val classifierKey = runBlocking { normalizer.normalize(rawLidl, autoCreate = false) }.canonical.normalizedName
        val rows = runBlocking { dao.getAll() }

        assertEquals(1, rows.size)
        assertTrue("stored key '${rows[0].merchantPattern}' must equal classifier key", rows[0].merchantPattern.equals(classifierKey, ignoreCase = true))
        assertEquals(dining, classify(rawLidl))
        assertTrue("the raw, un-normalized text is not the stored key", rows[0].merchantPattern != rawLidl)
    }

    @Test
    fun DR04_cachesInvalidated_readAfterCorrectionReturnsNewValueAlthoughOldWasCached() {
        seedLidlGroceries()
        assertEquals("primes the cache with Groceries", groceries, classify(rawLidl))
        assertEquals("served from cache", groceries, classify(rawLidl))

        runBlocking { repository.correctPattern(rawLidl, dining) }

        assertEquals(dining, classify(rawLidl))
    }

    @Test
    fun DR04_blockedWriteBarrier_noWrite_andExpectedFailure() {
        seedLidlGroceries()
        assertEquals(groceries, classify(rawLidl))
        mode = RestoreMaintenanceMode.Mode.RESTORE_STAGING

        try {
            runBlocking { repository.correctPattern(rawLidl, dining) }
            fail("expected DatabaseAccessBlockedException")
        } catch (expected: DatabaseAccessBlockedException) {
            // expected
        }

        mode = RestoreMaintenanceMode.Mode.NORMAL
        assertEquals(groceries, runBlocking { repository.getCategoryForMerchant(rawLidl) }!!.categoryId)
        assertEquals(groceries, classify(rawLidl))
        assertEquals(1, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_cancellationFromTheEngine_propagatesFromCorrectPattern() {
        val failingEngine = mockk<CategorizationEngine>()
        coEvery { failingEngine.correctMerchantCategory(any(), any()) } throws CancellationException("cancelled")
        val subject = MerchantCategoryRepository(
            writeBarrier = DatabaseWriteBarrier(mockk(relaxed = true)),
            dao = dao,
            categorizationEngineProvider = javax.inject.Provider { failingEngine }
        )

        var rethrown = false
        try {
            runBlocking { subject.correctPattern(rawLidl, dining) }
        } catch (e: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        assertEquals(0, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_cancellationFromTheDao_propagatesFromUpsertUserCorrection_andDoesNotInvalidateCaches() {
        val failingDao = mockk<MerchantCategoryDao>()
        coEvery { failingDao.upsertUserCorrection(any()) } throws CancellationException("cancelled")
        val spyEngine = mockk<CategorizationEngine>(relaxed = true)
        val maintenance = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { maintenance.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        val subject = MerchantCategoryRepository(
            writeBarrier = DatabaseWriteBarrier(maintenance),
            dao = failingDao,
            categorizationEngineProvider = javax.inject.Provider { spyEngine }
        )

        var rethrown = false
        try {
            runBlocking { subject.upsertUserCorrection(engineMapping()) }
        } catch (e: CancellationException) {
            rethrown = true
        }

        assertTrue(rethrown)
        coVerify(exactly = 0) { spyEngine.invalidateAllCaches() }
    }

    private fun engineMapping() = runBlocking { engine.createMerchantCategoryMapping(rawLidl, dining) }

    // ---- Correction ranking: case-variant rivals sharing the canonical name ----

    /** Seeds a differently cased "Lidl" row (same canonical name as the corrected "lidl" key). */
    private fun seedCaseVariantLidl(): MerchantCategory = runBlocking {
        val base = engine.createMerchantCategoryMapping("lidl", groceries)
        val seeded = base.copy(merchantPattern = "Lidl", confidence = 1.0f, timesUsed = 1)
        assertEquals("precondition: same canonical name", "lidl", seeded.normalizedCanonicalName)
        assertTrue(repository.insert(seeded) is MerchantCategoryInsertResult.Inserted)
        engine.invalidateAllCaches()
        seeded
    }

    private fun lookupByCanonical(canonical: String) =
        runBlocking { repository.getCategoryByNormalizedCanonical(canonical) }

    @Test
    fun DR04_caseVariantSeedLidl_correctionToB_winsDictionaryLookupAndCategorize_nonVacuity() {
        seedCaseVariantLidl()
        assertEquals("non-vacuity: without the correction Groceries", groceries, lookupByCanonical("lidl")!!.categoryId)
        assertEquals("non-vacuity: without the correction Groceries", groceries, classify("lidl"))

        runBlocking { engine.correctMerchantCategory("lidl", dining) }

        assertEquals(dining, lookupByCanonical("lidl")!!.categoryId)
        assertEquals(dining, classify("lidl"))
    }

    @Test
    fun DR04_caseVariantSeedLidl_correctedToBThenC_cWinsBothLookups_noThirdRow() {
        seedCaseVariantLidl()

        runBlocking { engine.correctMerchantCategory("lidl", dining) }
        assertEquals(2, runBlocking { dao.getCount() })

        runBlocking { engine.correctMerchantCategory("lidl", transport) }

        assertEquals(transport, lookupByCanonical("lidl")!!.categoryId)
        assertEquals(transport, classify("lidl"))
        assertEquals("seed row plus one corrected row, no third", 2, runBlocking { dao.getCount() })
    }

    @Test
    fun DR04_rivalWithTimesUsedAboveCorrectionConstant_correctionGetsRivalPlusOne_andWins() {
        val base = runBlocking { engine.createMerchantCategoryMapping("lidl", groceries) }
        val rival = MerchantCategory(
            merchantPattern = "lidl athens",
            categoryId = groceries,
            confidence = 1.0f,
            timesUsed = 2_000_000,
            normalizedCanonicalName = base.normalizedCanonicalName
        )
        assertTrue(repository.let { r -> runBlocking { r.insert(rival) } } is MerchantCategoryInsertResult.Inserted)
        engine.let { e -> runBlocking { e.invalidateAllCaches() } }
        assertEquals("precondition: rival wins", groceries, lookupByCanonical("lidl")!!.categoryId)

        runBlocking { engine.correctMerchantCategory("lidl", dining) }

        val corrected = runBlocking { repository.getCategoryForMerchant("lidl") }!!
        assertEquals(2_000_001, corrected.timesUsed)
        assertEquals(dining, corrected.categoryId)
        assertEquals(dining, lookupByCanonical("lidl")!!.categoryId)
        assertEquals(dining, classify("lidl"))
    }

    @Test
    fun DR04_sortOrder_equalLengthUncorrectedRowStaysAsItWas() {
        val seeded = seedCaseVariantLidl()

        runBlocking { engine.correctMerchantCategory("lidl", dining) }

        val stillThere = runBlocking { dao.getCategoryForMerchant("Lidl") }!!
        assertEquals(groceries, stillThere.categoryId)
        assertEquals(seeded.timesUsed, stillThere.timesUsed)
        assertEquals(seeded.confidence, stillThere.confidence, 0.0f)
        assertEquals(dining, classify("lidl"))
    }

    @Test
    fun DR04_sortOrder_longerSpecificMappingIsNotBeatenByShorterCorrectedRow() {
        val longRaw = "Lidl Athens"
        val longRow = runBlocking { engine.createMerchantCategoryMapping(longRaw, groceries) }
        val shortKey = runBlocking { engine.createMerchantCategoryMapping("lidl", groceries) }.merchantPattern
        assertTrue(
            "precondition: normalizer keeps the longer key '${longRow.merchantPattern}' longer than '$shortKey'",
            longRow.merchantPattern.length > shortKey.length
        )
        assertTrue(runBlocking { repository.insert(longRow) } is MerchantCategoryInsertResult.Inserted)
        engine.let { e -> runBlocking { e.invalidateAllCaches() } }
        assertEquals("precondition: long mapping is read", groceries, classify(longRaw))

        runBlocking { engine.correctMerchantCategory("lidl", dining) }

        assertEquals("the shorter corrected row applies to its own key", dining, classify("lidl"))
        assertEquals("the longer specific mapping is not beaten", groceries, classify(longRaw))
    }

    @Test
    fun DR04_correctedLidl_doesNotChangeClassificationOfADifferentMerchant() {
        val other = runBlocking { engine.createMerchantCategoryMapping("Sklavenitis", groceries) }
        assertTrue(runBlocking { repository.insert(other) } is MerchantCategoryInsertResult.Inserted)
        engine.let { e -> runBlocking { e.invalidateAllCaches() } }
        assertEquals("precondition", groceries, classify("Sklavenitis"))

        runBlocking { engine.correctMerchantCategory("lidl", dining) }

        assertEquals(groceries, classify("Sklavenitis"))
        assertEquals(groceries, runBlocking { dao.getCategoryForMerchant(other.merchantPattern) }!!.categoryId)
        assertEquals(dining, classify("lidl"))
    }
}
