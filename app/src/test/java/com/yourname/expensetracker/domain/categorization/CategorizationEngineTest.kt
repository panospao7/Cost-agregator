package com.yourname.expensetracker.domain.categorization

import android.content.Context
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.data.database.entity.MerchantCanonical
import com.yourname.expensetracker.domain.intelligence.ml.MerchantLookupResult
import com.yourname.expensetracker.domain.intelligence.ml.MatchType as MLMatchType
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.MatchType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class CategorizationEngineTest {
    private val context = mockk<Context>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<NewMerchantNormalizer>(relaxed = true)
    private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
    private val categoryRepositoryProvider = mockk<javax.inject.Provider<CategoryRepository>>()
    private val canonicalizer = mockk<MerchantCanonicalizer>(relaxed = true)
    private val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
    private val semanticMatcher = mockk<SemanticKeywordMatcher>(relaxed = true)
    private val contextEngine = mockk<ContextualInferenceEngine>(relaxed = true)
    private val timeProvider = mockk<TimeProvider>(relaxed = true)
    private lateinit var engine: CategorizationEngine

    @Before
    fun setup() {
        coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
            val name = firstArg<String>().uppercase()
            MerchantLookupResult(
                canonical = MerchantCanonical(normalizedName = name, searchKey = name.lowercase()),
                alias = null,
                confidence = 1.0f,
                matchType = MLMatchType.EXACT_MATCH
            )
        }
        every { categoryRepositoryProvider.get() } returns categoryRepository
        every { timeProvider.now() } returns 1_710_000_000_000L
        coEvery { categoryRepository.getAll() } returns emptyList()
        engine = CategorizationEngine(
            merchantCategoryRepository,
            merchantNormalizer,
            categoryRepositoryProvider,
            canonicalizer,
            greeklishNormalizer,
            semanticMatcher,
            contextEngine,
            timeProvider
        )
    }

    @Test
    fun `normalize uppercases`() = runTest {
        assertEquals("STARBUCKS", engine.normalize("starbucks"))
        assertEquals("UBER-EATS", engine.normalize("uber-eats"))
    }

    @Test
    fun `normalize handles Greek characters`() = runTest {
        val result = engine.normalize("ΣΚΛΑΒΕΝΙΤΗΣ")
        assertTrue(result.contains("ΣΚΛΑΒΕΝΙΤΗΣ"))
    }

    @Test
    fun `exact match returns category`() = runTest {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 5L)
        )

        val result = engine.categorize("starbucks")
        assertEquals(5L, result.categoryId)
        assertEquals(MatchType.EXACT, result.matchType)
    }

    @Test
    fun `substring match finds pattern within merchant name`() = runTest {
        coEvery { merchantCategoryRepository.getCategoryForMerchant("UBER EATS DELIVERY 1234") } returns null
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("uber eats", 3L),
            MerchantCategory("uber", 4L)
        )

        val result = engine.categorize("UBER EATS")
        // Exact match on "UBER EATS" should return category 3
        assertEquals(3L, result.categoryId)
    }

    @Test
    fun `categorize uses cached data on second call`() = runTest {
        coEvery { merchantCategoryRepository.getAll() } returns listOf(
            MerchantCategory("starbucks", 1L)
        )

        // First call: should fetch from repository
        engine.categorize("starbucks")
        coVerify(exactly = 1) { merchantCategoryRepository.getAll() }

        // Second call: should use cache, not re-fetch
        engine.categorize("starbucks")
        coVerify(exactly = 1) { merchantCategoryRepository.getAll() } // still exactly 1
    }

    @Test
    fun `returns unknown when no match found`() = runTest {
        coEvery { merchantCategoryRepository.getCategoryForMerchant(any()) } returns null
        coEvery { merchantCategoryRepository.getAll() } returns emptyList()

        val result = engine.categorize("COMPLETELY UNKNOWN MERCHANT")
        assertNull(result.categoryId)
        assertEquals(MatchType.UNKNOWN, result.matchType)
        assertEquals(0.0, result.confidence, 0.01)
    }

    // ------------------------------------------------------------------
    // Merged from CategorizationEngineStressTest: stateful / concurrency cases
    // ------------------------------------------------------------------

    @Test
    fun `10000 concurrent categorization requests raise no errors`() {
        runBlocking {
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory("starbucks", 1L),
                MerchantCategory("mcdonalds", 2L),
                MerchantCategory("amazon", 3L)
            )
            coEvery { categoryRepository.getAll() } returns listOf(
                mockk { every { id } returns 1L; every { name } returns "Food" }
            )

            val executor = Executors.newFixedThreadPool(10)
            val latch = CountDownLatch(10000)
            val errors = AtomicInteger(0)

            repeat(10000) {
                executor.submit {
                    try {
                        runBlocking {
                            engine.categorize("starbucks")
                        }
                    } catch (e: Exception) {
                        errors.incrementAndGet()
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await(60, TimeUnit.SECONDS)
            executor.shutdown()

            assertEquals(0, errors.get())
        }
    }

    @Test
    fun `invalidateCache makes next categorization see new repository data`() {
        runBlocking {
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory("shop", 1L)
            ) andThen listOf(
                MerchantCategory("shop", 2L), // Different category after invalidate
                MerchantCategory("new", 3L)
            )

            val result1 = engine.categorize("shop")
            assertEquals(1L, result1.categoryId)

            engine.invalidateCache()

            val result2 = engine.categorize("shop")
            assertEquals(2L, result2.categoryId)
        }
    }

    @Test
    fun `all layers failing returns UNKNOWN with zero confidence`() {
        runBlocking {
            coEvery { merchantCategoryRepository.getAll() } returns emptyList()
            every { canonicalizer.canonicalize(any()) } returns CanonicalResult("unknown", emptyList(), 0.0)
            every { greeklishNormalizer.getVariations(any()) } returns emptyList()
            every { greeklishNormalizer.normalize(any()) } returns "unknown"
            every { semanticMatcher.findBestMatch(any(), any()) } returns
                SemanticKeywordMatcher.KeywordMatchResult(null, emptyList(), false)
            every { contextEngine.isLikelySurname(any()) } returns false

            val result = engine.categorize("completelyunknownmerchant12345")

            assertEquals(MatchType.UNKNOWN, result.matchType)
            assertNull(result.categoryId)
            assertEquals(0.0, result.confidence, 0.001)
        }
    }

    @Test
    fun `canonical layer confidence is exact confidence minus canonical penalty`() {
        runBlocking {
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory("cafe", 1L) // Only exact match
            )

            val exact = engine.categorize("cafe")
            assertEquals(0.98, exact.confidence, 0.01)

            every { canonicalizer.canonicalize(any()) } returns CanonicalResult("cafe", emptyList(), 0.1)

            val canonical = engine.categorize("CAFEBAR")
            assertEquals(0.93 - 0.1, canonical.confidence, 0.01)
        }
    }

    @Test
    fun `canonical match confidence stays below exact and at or above 0_85`() {
        runBlocking {
            coEvery { merchantCategoryRepository.getAll() } returns listOf(
                MerchantCategory("cafe", 1L)
            )
            every { canonicalizer.canonicalize("cafebl") } returns
                CanonicalResult("cafe", emptyList(), 0.05)

            val result = engine.categorize("cafebl")
            assertTrue(result.confidence < 0.98)
            assertTrue(result.confidence >= 0.85)
        }
    }

    @Test
    fun `concurrent cache updates return one result per request`() {
        runBlocking {
            val categories = (1..100).map { MerchantCategory("merchant$it", it.toLong()) }
            coEvery { merchantCategoryRepository.getAll() } returns categories
            coEvery { categoryRepository.getAll() } returns emptyList()

            val results = coroutineScope {
                (1..100).map { i ->
                    async {
                        engine.categorize("merchant${i % 100}")
                    }
                }.awaitAll()
            }

            assertEquals(100, results.size)
        }
    }

    @Test
    fun `fuzz random merchant names never crash categorize`() {
        val random = Random(20260101)
        var executed = 0
        runBlocking {
            repeat(1000) {
                val randomName = (1..30).map {
                    when (random.nextInt(5)) {
                        0 -> ('a'..'z').random(random)
                        1 -> ('Α'..'Ω').random(random)
                        2 -> ('0'..'9').random(random)
                        3 -> " -_".random(random)
                        else -> ""
                    }
                }.joinToString("")

                try {
                    engine.categorize(randomName)
                } catch (e: Exception) {
                    fail("Crashed with: $randomName")
                }
                executed++
            }
        }
        assertEquals(1000, executed)
    }

    // ------------------------------------------------------------------
    // Merged from CategorizationEngineStressTest: table-driven scenarios.
    // Each row runs on a fresh engine + mocks (the engine caches repository
    // data, so rows must not share an engine).
    // ------------------------------------------------------------------

    private class EngineFixture {
        val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
        val merchantNormalizer = mockk<NewMerchantNormalizer>(relaxed = true)
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        val categoryRepositoryProvider = mockk<javax.inject.Provider<CategoryRepository>>()
        val canonicalizer = mockk<MerchantCanonicalizer>(relaxed = true)
        val greeklishNormalizer = mockk<GreeklishNormalizer>(relaxed = true)
        val semanticMatcher = mockk<SemanticKeywordMatcher>(relaxed = true)
        val contextEngine = mockk<ContextualInferenceEngine>(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val engine: CategorizationEngine

        init {
            coEvery { merchantNormalizer.normalize(any(), any(), any()) } answers {
                val name = firstArg<String>()
                MerchantLookupResult(
                    canonical = MerchantCanonical(normalizedName = name.uppercase(), searchKey = name.lowercase()),
                    alias = null,
                    confidence = 1.0f,
                    matchType = MLMatchType.EXACT_MATCH
                )
            }
            every { categoryRepositoryProvider.get() } returns categoryRepository
            every { timeProvider.now() } returns 1_710_000_000_000L
            coEvery { categoryRepository.getAll() } returns emptyList()
            engine = CategorizationEngine(
                merchantCategoryRepository,
                merchantNormalizer,
                categoryRepositoryProvider,
                canonicalizer,
                greeklishNormalizer,
                semanticMatcher,
                contextEngine,
                timeProvider
            )
        }
    }

    private data class Scenario(
        val label: String,
        val input: String,
        val repo: List<MerchantCategory> = emptyList(),
        val canonical: CanonicalResult? = null,
        val variations: List<String>? = null,
        val normalizedTo: String? = null,
        val calls: Int = 1,
        val expectedId: Long? = null,
        val expectIdNull: Boolean = false,
        val expectIdNonNull: Boolean = false,
        val expectedType: MatchType? = null,
        val expectedConfidence: Double? = null
    )

    private fun runScenario(s: Scenario): CategorizationResult = runBlocking {
        val f = EngineFixture()
        coEvery { f.merchantCategoryRepository.getAll() } returns s.repo
        s.canonical?.let { every { f.canonicalizer.canonicalize(s.input) } returns it }
        s.variations?.let { every { f.greeklishNormalizer.getVariations(any()) } returns it }
        s.normalizedTo?.let { every { f.greeklishNormalizer.normalize(any()) } returns it }
        var result = f.engine.categorize(s.input)
        repeat(s.calls - 1) { result = f.engine.categorize(s.input) }
        result
    }

    private fun diphthong(greek: String, vararg latinVariations: String): Scenario =
        Scenario(
            label = "diphthong $greek",
            input = greek,
            repo = listOf(MerchantCategory(latinVariations.first().lowercase(), 1L)),
            variations = listOf(greek) + latinVariations.toList(),
            normalizedTo = greek.lowercase()
        )

    private val lookupScenarios: List<Scenario> = listOf(
        Scenario(
            "exact match still has highest confidence", "starbucks",
            repo = listOf(MerchantCategory("starbucks", 1L)),
            expectedId = 1L, expectedType = MatchType.EXACT, expectedConfidence = 0.98
        ),
        Scenario(
            "second lookup served from cache stays EXACT", "test",
            repo = listOf(MerchantCategory("test", 1L)),
            calls = 2, expectedType = MatchType.EXACT
        ),
        Scenario(
            "large dictionary resolves exact match to correct category", "merchant2500",
            repo = (1..5000).map { MerchantCategory("merchant$it", it.toLong()) },
            expectedId = 2500L, expectedType = MatchType.EXACT
        ),
        Scenario(
            "short names: exact match wins even though fuzzy is disabled", "ab",
            repo = listOf(MerchantCategory("ab", 1L), MerchantCategory("ac", 2L), MerchantCategory("ad", 3L)),
            expectedId = 1L
        ),
        Scenario(
            "short names: unrelated name does not match", "zz",
            repo = listOf(MerchantCategory("ab", 1L), MerchantCategory("ac", 2L), MerchantCategory("ad", 3L)),
            expectIdNull = true
        ),
        Scenario(
            "edit distance 1 from known merchant fuzzy matches", "starbuks",
            repo = listOf(MerchantCategory("starbucks", 1L)),
            canonical = CanonicalResult("starbuks", emptyList(), 0.0),
            expectIdNonNull = true
        ),
        Scenario(
            "edit distance 2 from 9-char merchant does not fuzzy match", "starbks",
            repo = listOf(MerchantCategory("starbucks", 1L)),
            canonical = CanonicalResult("starbks", emptyList(), 0.0),
            expectIdNull = true
        ),
        Scenario(
            "merchants shorter than 4 characters disable fuzzy", "ac",
            repo = listOf(MerchantCategory("ab", 1L)),
            canonical = CanonicalResult("ac", emptyList(), 0.0),
            expectIdNull = true
        ),
        Scenario(
            "three character merchants can use fuzzy matching", "abd",
            repo = listOf(MerchantCategory("abc", 1L)),
            canonical = CanonicalResult("abd", emptyList(), 0.0),
            expectedId = 1L
        ),
        Scenario(
            "4-char merchant cafe fuzzy matches cafex", "cafex",
            repo = listOf(MerchantCategory("cafe", 1L), MerchantCategory("ab", 2L)),
            canonical = CanonicalResult("cafe", emptyList(), 0.0),
            expectIdNonNull = true
        ),
        Scenario(
            "canonical layer matches short name when canonicalization collapses to exact key", "ac",
            repo = listOf(MerchantCategory("cafe", 1L), MerchantCategory("ab", 2L)),
            canonical = CanonicalResult("ab", emptyList(), 0.0),
            expectedId = 2L
        ),
        Scenario("empty merchant name is UNKNOWN", "", expectedType = MatchType.UNKNOWN),
        Scenario("whitespace only merchant name is UNKNOWN", "   ", expectedType = MatchType.UNKNOWN),
        Scenario("empty repository yields UNKNOWN", "anything", expectedType = MatchType.UNKNOWN)
    )

    @Test
    fun `lookup scenarios resolve expected category and match type`() {
        assertTrue("lookupScenarios must not be empty", lookupScenarios.isNotEmpty())
        lookupScenarios.forEach { s ->
            val msg = "scenario '${s.label}'"
            val r = runScenario(s)
            if (s.expectIdNull) assertNull("$msg categoryId", r.categoryId)
            if (s.expectIdNonNull) assertNotNull("$msg categoryId", r.categoryId)
            val expectedId: Long? = s.expectedId
            if (expectedId != null) {
                val actualId: Long? = r.categoryId
                assertEquals("$msg categoryId", expectedId, actualId)
            }
            val expectedType: MatchType? = s.expectedType
            if (expectedType != null) assertEquals("$msg matchType", expectedType, r.matchType)
            val expectedConfidence: Double? = s.expectedConfidence
            if (expectedConfidence != null) {
                assertEquals("$msg confidence", expectedConfidence, r.confidence, 0.01)
            }
        }
    }

    // Rows below only require that categorize() returns normally (no exception).
    // The stress suite's assertNotNull(result) on the non-null CategorizationResult
    // return type was provably vacuous and is replaced by the explicit no-throw guard.
    private val noThrowScenarios: List<Scenario> = listOf(
        diphthong("ΜΠΗ", "MPI", "BI"),
        diphthong("ΟΥ", "OY", "U"),
        diphthong("ΑΙ", "AI", "E"),
        diphthong("ΕΙ", "EI", "I"),
        diphthong("ΟΙ", "OI", "I"),
        diphthong("ΓΓ", "GG", "NG"),
        diphthong("ΓΚ", "GK", "NK"),
        diphthong("ΓΧ", "GX", "NX"),
        diphthong("ΤΖ", "TZ", "Z"),
        diphthong("ΤΣ", "TS", "S"),
        diphthong("ΔΖ", "DZ", "Z"),
        Scenario("mixed case Greek ΣΚΛΑΒΕΝΙΤΗΣ", "ΣΚΛΑΒΕΝΙΤΗΣ",
            repo = listOf(MerchantCategory("σκλαβενιτης", 1L)), variations = listOf("σκλαβενιτης")),
        Scenario("mixed case Greek σκλαβενιτης", "σκλαβενιτης",
            repo = listOf(MerchantCategory("σκλαβενιτης", 1L)), variations = listOf("σκλαβενιτης")),
        Scenario("mixed case Greek Σκλαβενιτης", "Σκλαβενιτης",
            repo = listOf(MerchantCategory("σκλαβενιτης", 1L)), variations = listOf("σκλαβενιτης")),
        Scenario("mixed case Greek σΚλΑβΕνΙτΗς", "σΚλΑβΕνΙτΗς",
            repo = listOf(MerchantCategory("σκλαβενιτης", 1L)), variations = listOf("σκλαβενιτης")),
        Scenario("edit distance 3 from known merchant", "stbks",
            repo = listOf(MerchantCategory("starbucks", 1L)),
            canonical = CanonicalResult("stbks", emptyList(), 0.0)),
        Scenario("cache populated then read 100 times", "merchant50",
            repo = (1..1000).map { MerchantCategory("merchant$it", it.toLong()) }, calls = 100),
        Scenario("Greek unicode Σκλαβενίτης", "Σκλαβενίτης",
            repo = listOf(MerchantCategory("Σκλαβενίτης".lowercase(), 1L)), variations = emptyList()),
        Scenario("Greek unicode Μασούτης", "Μασούτης",
            repo = listOf(MerchantCategory("Μασούτης".lowercase(), 1L)), variations = emptyList()),
        Scenario("Greek unicode Βασιλόπουλος", "Βασιλόπουλος",
            repo = listOf(MerchantCategory("Βασιλόπουλος".lowercase(), 1L)), variations = emptyList()),
        Scenario("Greek unicode ΑΒ Βασιλόπουλος", "ΑΒ Βασιλόπουλος",
            repo = listOf(MerchantCategory("ΑΒ Βασιλόπουλος".lowercase(), 1L)), variations = emptyList()),
        Scenario("Greek unicode Κωτσόβολος", "Κωτσόβολος",
            repo = listOf(MerchantCategory("Κωτσόβολος".lowercase(), 1L)), variations = emptyList()),
        Scenario("mixed script LIDL Ελλάδος", "LIDL Ελλάδος"),
        Scenario("mixed script ABC 123", "ABC 123"),
        Scenario("mixed script SHOP-GR", "SHOP-GR"),
        Scenario("mixed script Market2024", "Market2024"),
        Scenario("mixed script Café Greece", "Café Greece"),
        Scenario("special chars Shop & Save", "Shop & Save"),
        Scenario("special chars A/B Testing", "A/B Testing"),
        Scenario("special chars Café-Star", "Café-Star"),
        Scenario("special chars Food+More", "Food+More"),
        Scenario("special chars The Store - Main", "The Store - Main"),
        Scenario("special chars McDonald's #1", "McDonald's #1"),
        Scenario("special chars 7-Eleven", "7-Eleven"),
        Scenario("special chars 100% Greek", "100% Greek"),
        Scenario("emoji Shop money bag", "Shop 💰"),
        Scenario("emoji money first", "💵 Money"),
        Scenario("emoji five stars", "⭐⭐⭐⭐⭐"),
        Scenario("emoji Cafe hot beverage", "Café ☕"),
        Scenario("emoji store first", "🏪 Store"),
        Scenario("ambiguity AMAZON", "AMAZON",
            repo = listOf(MerchantCategory("amazon", 1L)), variations = emptyList()),
        Scenario("ambiguity AMAZON.COM", "AMAZON.COM",
            repo = listOf(MerchantCategory("amazon", 1L)), variations = emptyList()),
        Scenario("ambiguity AMZN", "AMZN",
            repo = listOf(MerchantCategory("amazon", 1L)), variations = emptyList()),
        Scenario("ambiguity Amazon Market", "Amazon Market",
            repo = listOf(MerchantCategory("amazon", 1L)), variations = emptyList()),
        Scenario("ambiguity Amazon EU", "Amazon EU",
            repo = listOf(MerchantCategory("amazon", 1L)), variations = emptyList()),
        Scenario("common prefix coffee shoppe", "coffee shoppe",
            repo = listOf(
                MerchantCategory("coffee shop", 1L),
                MerchantCategory("coffee house", 2L),
                MerchantCategory("coffee bean", 3L)
            ),
            canonical = CanonicalResult("coffee shoppe", emptyList(), 0.0)),
        Scenario("very long merchant name", "A".repeat(1000)),
        Scenario("NUL character in name", "Shop\u0000Test"),
        Scenario("control characters in name", "Shop\u0001\u0002Test")
    )

    @Test
    fun `hostile and unusual merchant inputs do not throw`() {
        assertTrue("noThrowScenarios must not be empty", noThrowScenarios.isNotEmpty())
        noThrowScenarios.forEach { s ->
            try {
                runScenario(s)
            } catch (e: Exception) {
                fail("scenario '${s.label}' threw ${e::class.java.simpleName}")
            }
        }
    }
}
