package com.yourname.expensetracker.domain.quickadd

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.data.repository.MerchantNormalizationRepository
import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.categorization.GreeklishNormalizer
import com.yourname.expensetracker.domain.categorization.MerchantCanonicalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * nl-quick-add slice C: C2 learned-category lookup (spec 4.11, INV-10, INV-11, AE-1/3/4 learned parts, AE-40).
 *
 * AE-40 is proven twice (plan H-1 and amendment verdict 3): write-counting collaborators with
 * `verify(exactly = 0)` on every write method (primary), and a literal Robolectric in-memory Room
 * row-count assertion with the REAL DAO and repository. The class uses the Robolectric runner because
 * of the second proof; nothing else here needs Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuickAddLearnedCategoryLookupTest {

    private val pickerCategories = listOf(CategoryRef(2L, "Transport"), CategoryRef(3L, "Food"))

    private val merchantNormalizer =
        mockk<com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer>(relaxed = true)
    private val semanticMatcher = mockk<com.yourname.expensetracker.domain.categorization.SemanticKeywordMatcher>(relaxed = true)
    private val contextEngine = mockk<com.yourname.expensetracker.domain.categorization.ContextualInferenceEngine>(relaxed = true)

    /** The real engine, so the REAL key function is exercised; every other collaborator is a mock. */
    private fun realEngine(
        repository: MerchantCategoryRepository,
        normalizer: com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer = merchantNormalizer
    ) = CategorizationEngine(
        merchantCategoryRepository = repository,
        merchantNormalizer = normalizer,
        categoryRepositoryProvider = mockk(),
        canonicalizer = MerchantCanonicalizer(),
        greeklishNormalizer = GreeklishNormalizer(),
        semanticMatcher = semanticMatcher,
        contextEngine = contextEngine,
        timeProvider = mockk(relaxed = true)
    )

    private fun learnedRow(categoryId: Long) =
        MerchantCategory(merchantPattern = "KAFES", categoryId = categoryId, normalizedCanonicalName = "kafes")

    private fun lookup(repository: MerchantCategoryRepository) =
        QuickAddLearnedCategoryLookup(realEngine(repository), repository)

    private fun doLookup(
        lookup: QuickAddLearnedCategoryLookup,
        merchant: String,
        categories: List<CategoryRef> = pickerCategories
    ): LearnedCategoryLookupResult = runBlocking { lookup.lookup(MerchantText(merchant), categories) }

    private fun verifyNoWritesAndNoBannedReads(repository: MerchantCategoryRepository) {
        coVerify(exactly = 0) { repository.insert(any()) }
        coVerify(exactly = 0) { repository.learnPattern(any(), any()) }
        coVerify(exactly = 0) { repository.getCategoryForMerchant(any()) }
        coVerify(exactly = 0) { repository.getAll() }
        // The engine's other collaborators must never be touched (no categorize*, no normalize, no trace).
        confirmVerified(merchantNormalizer, semanticMatcher, contextEngine)
    }

    // ---- AE-1 / AE-3 / AE-4 learned parts ----

    @Test
    fun `AE-1 learned row keyed by the real key function is found and used`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical("kafes") } returns learnedRow(3L)

        val result = doLookup(lookup(repository), "καφές")

        assertEquals(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)), result)
        coVerify(exactly = 1) { repository.getCategoryByNormalizedCanonical("kafes") }
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `AE-3 learned taxi row maps to Transport`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical("taxi") } returns
            MerchantCategory(merchantPattern = "TAXI", categoryId = 2L, normalizedCanonicalName = "taxi")

        val result = doLookup(lookup(repository), "taxi")

        assertEquals(LearnedCategoryLookupResult.Found(CategoryMatch(2L, CategorySource.LEARNED)), result)
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `AE-4 Greek and Latin spellings derive the same key and find the same row`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical("kafes") } returns learnedRow(3L)
        val engine = realEngine(repository)
        assertEquals(
            engine.normalizedCanonicalNameForMerchant("kafes"),
            engine.normalizedCanonicalNameForMerchant("καφές")
        )
        // The spec's key identity (AE-4) on the single canonical key function itself.
        assertEquals(
            com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("kafes"),
            com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("καφές")
        )
        val subject = QuickAddLearnedCategoryLookup(engine, repository)

        val latin = doLookup(subject, "kafes")
        val greek = doLookup(subject, "καφές")

        assertEquals(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)), latin)
        assertEquals(latin, greek)
        coVerify(exactly = 2) { repository.getCategoryByNormalizedCanonical("kafes") }
        verifyNoWritesAndNoBannedReads(repository)
    }

    // ---- Absent cases ----

    @Test
    fun `no learned row gives Absent`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical(any()) } returns null

        assertEquals(LearnedCategoryLookupResult.Absent, doLookup(lookup(repository), "unknown shop"))
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `learned category that is not in the picker gives Absent`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical("kafes") } returns learnedRow(99L)

        assertEquals(LearnedCategoryLookupResult.Absent, doLookup(lookup(repository), "kafes"))
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `blank derived key gives Absent without reading the repository`() {
        val repository = mockk<MerchantCategoryRepository>()

        assertEquals(LearnedCategoryLookupResult.Absent, doLookup(lookup(repository), "   "))
        coVerify(exactly = 0) { repository.getCategoryByNormalizedCanonical(any()) }
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `empty picker gives Absent without reading the repository`() {
        val repository = mockk<MerchantCategoryRepository>()

        assertEquals(LearnedCategoryLookupResult.Absent, doLookup(lookup(repository), "kafes", categories = emptyList()))
        coVerify(exactly = 0) { repository.getCategoryByNormalizedCanonical(any()) }
        verifyNoWritesAndNoBannedReads(repository)
    }

    // ---- failure and cancellation ----

    @Test
    fun `repository exception gives a failure result and carries no message`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical(any()) } throws
            SQLiteException("no such column near WHERE normalizedCanonicalName = 'kafes' secret=42")

        val result = doLookup(lookup(repository), "kafes")

        assertEquals(LearnedCategoryLookupResult.Failed, result)
        assertFalse(result.toString().contains("kafes"))
        assertFalse(result.toString().contains("secret"))
        verifyNoWritesAndNoBannedReads(repository)
    }

    @Test
    fun `CancellationException from the repository is rethrown and never turned into a failure`() {
        val repository = mockk<MerchantCategoryRepository>()
        coEvery { repository.getCategoryByNormalizedCanonical(any()) } throws CancellationException("cancelled")
        val subject = lookup(repository)

        var rethrown = false
        runBlocking {
            try {
                subject.lookup(MerchantText("kafes"), pickerCategories)
            } catch (e: CancellationException) {
                rethrown = true
            }
        }

        assertTrue(rethrown)
    }

    // ---- AE-40 literal: real Room, real DAO, real repository, row counts unchanged ----

    @Test
    fun `AE-40 lookup against a real in-memory Room leaves every table row count unchanged`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val queryExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "qa-lookup-query").apply { isDaemon = true } }
        val transactionExecutor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "qa-lookup-tx").apply { isDaemon = true } }
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        try {
            val maintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)
            every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            every { maintenanceMode.isWritesAllowed() } returns true
            lateinit var engine: CategorizationEngine
            val repository = MerchantCategoryRepository(
                writeBarrier = DatabaseWriteBarrier(maintenanceMode),
                dao = database.merchantCategoryDao(),
                categorizationEngineProvider = javax.inject.Provider { engine }
            )
            // REAL normalizer over the in-memory DB (not a mock): if the lookup ever reached
            // MerchantNormalizer.normalize(autoCreate = false), the seeded fuzzy-near canonical below
            // (>= 0.95) would make it INSERT/UPDATE a merchant_aliases row and this test would fail.
            val timeProvider = mockk<TimeProvider>()
            every { timeProvider.now() } returns 1_700_000_000_000L
            val realNormalizer = com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer(
                repository = MerchantNormalizationRepository(
                    writeBarrier = DatabaseWriteBarrier(maintenanceMode),
                    dao = database.merchantNormalizationDao(),
                    timeProvider = timeProvider
                ),
                merchantRules = MerchantRulesRepository(),
                greeklishNormalizer = GreeklishNormalizer(),
                context = context,
                timeProvider = timeProvider
            )
            engine = realEngine(repository, realNormalizer)

            // Seed (test setup, before the measurement): a category, a learned row and a fuzzy-near row.
            runBlocking {
                database.categoryDao().insert(Category(id = 3L, name = "Food", icon = "F", color = "#FF0000"))
                database.categoryDao().insert(Category(id = 2L, name = "Transport", icon = "T", color = "#00FF00"))
                // Spec AE-40: canonical merchant "Καφές" (search key "kafes"); "kafess" is fuzzy-near it.
                database.merchantNormalizationDao().insertCanonical(
                    MerchantCanonical(
                        normalizedName = "Καφές",
                        searchKey = com.yourname.expensetracker.domain.util.MerchantKeyGenerator.generate("Καφές"),
                        totalOccurrences = 1,
                        createdAt = 1_700_000_000_000L,
                        updatedAt = 1_700_000_000_000L
                    )
                )
            }
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL(
                "INSERT INTO merchant_categories (merchantPattern, categoryId, confidence, timesUsed, normalizedCanonicalName) VALUES (?, ?, 1.0, 1, ?)",
                arrayOf<Any>("KAFES", 3L, "kafes")
            )
            sqlite.execSQL(
                "INSERT INTO merchant_categories (merchantPattern, categoryId, confidence, timesUsed, normalizedCanonicalName) VALUES (?, ?, 1.0, 1, ?)",
                arrayOf<Any>("KAFESS", 2L, "kafess")
            )

            val before = rowCounts(database)
            assertEquals(2, before.getValue("merchant_categories"))
            assertEquals(0, before.getValue("expenses"))
            assertEquals(1, before.getValue("merchant_canonicals"))
            assertEquals(0, before.getValue("merchant_aliases"))

            val subject = QuickAddLearnedCategoryLookup(engine, repository)
            val hit = runBlocking { subject.lookup(MerchantText("καφές"), pickerCategories) }
            val fuzzyNear = runBlocking { subject.lookup(MerchantText("kafess"), pickerCategories) }
            val miss = runBlocking { subject.lookup(MerchantText("kafesss"), pickerCategories) }

            assertEquals(LearnedCategoryLookupResult.Found(CategoryMatch(3L, CategorySource.LEARNED)), hit)
            assertEquals(LearnedCategoryLookupResult.Found(CategoryMatch(2L, CategorySource.LEARNED)), fuzzyNear)
            assertEquals(LearnedCategoryLookupResult.Absent, miss)
            val after = rowCounts(database)
            assertEquals("merchant_aliases", before.getValue("merchant_aliases"), after.getValue("merchant_aliases"))
            assertEquals("merchant_canonicals", before.getValue("merchant_canonicals"), after.getValue("merchant_canonicals"))
            // The collaborators the lookup must never reach stay untouched.
            confirmVerified(semanticMatcher, contextEngine)
            assertEquals("merchant_categories", before.getValue("merchant_categories"), after.getValue("merchant_categories"))
            assertEquals("expenses", before.getValue("expenses"), after.getValue("expenses"))
            // No alias, canonical or event rows appear either: every table is unchanged.
            assertEquals(before, after)
        } finally {
            database.close()
            queryExecutor.shutdownNow()
            transactionExecutor.shutdownNow()
        }
    }

    private fun rowCounts(database: AppDatabase): Map<String, Int> {
        val db = database.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name <> 'android_metadata'"
        ).use { c -> while (c.moveToNext()) tables += c.getString(0) }
        return tables.sorted().associateWith { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                c.moveToFirst()
                c.getInt(0)
            }
        }
    }
}
