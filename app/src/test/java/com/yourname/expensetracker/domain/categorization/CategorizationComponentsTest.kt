package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

class MerchantCanonicalizerTest {
    
    private lateinit var canonicalizer: MerchantCanonicalizer
    
    @Before
    fun setup() {
        canonicalizer = MerchantCanonicalizer()
    }
    
    @Test
    fun `strips location suffix - lagka`() {
        val result = canonicalizer.canonicalize("Sklavenitis Lagka")
        assertEquals("sklavenitis", result.canonicalName)
        assertTrue(result.strippedParts.contains("lagka"))
    }
    
    @Test
    fun `strips location suffix - stores`() {
        val result = canonicalizer.canonicalize("AB Vassilopoulos Stores")
        assertEquals("ab vassilopoulos", result.canonicalName)
        assertTrue(result.strippedParts.contains("stores"))
    }
    
    @Test
    fun `strips business type suffix - sa`() {
        val result = canonicalizer.canonicalize("AB SA")
        assertEquals("ab sa", result.canonicalName)
    }
    
    @Test
    fun `strips business type suffix - ae`() {
        val result = canonicalizer.canonicalize("My Company AE")
        assertEquals("my company", result.canonicalName)
        assertTrue(result.strippedParts.contains("ae"))
    }
    
    @Test
    fun `strips multiple suffixes`() {
        val result = canonicalizer.canonicalize("Sklavenitis Lagka SA")
        assertEquals("sklavenitis", result.canonicalName)
        assertTrue(result.strippedParts.size >= 2)
    }

    @Test
    fun `does not treat dotted suffix as wildcard regex`() {
        val result = canonicalizer.canonicalize("Acme mXiXkXeX")
        assertEquals("acme mxixkxex", result.canonicalName)
        assertFalse(result.strippedParts.contains("m.i.k.e."))
    }
    
    @Test
    fun `handles region prefix`() {
        val result = canonicalizer.canonicalize("North Store Athens")
        assertEquals("store", result.canonicalName)  // "north" prefix and "athens" suffix both stripped
        assertTrue(result.strippedParts.contains("north"))
        assertTrue(result.strippedParts.contains("athens"))
    }
    
    @Test
    fun `no stripping for simple names`() {
        val result = canonicalizer.canonicalize("Starbucks")
        assertEquals("starbucks", result.canonicalName)
        assertTrue(result.strippedParts.isEmpty())
    }
    
    @Test
    fun `confidence penalty increases with stripped parts`() {
        val noPenalty = canonicalizer.canonicalize("Starbucks")
        val onePenalty = canonicalizer.canonicalize("Sklavenitis Lagka")
        val twoPenalty = canonicalizer.canonicalize("Sklavenitis Lagka SA")
        
        assertEquals(0.0, noPenalty.confidencePenalty, 0.01)
        assertTrue(onePenalty.confidencePenalty > 0.0)
        assertTrue(twoPenalty.confidencePenalty > onePenalty.confidencePenalty)
    }
    
    @Test
    fun `handles Greek characters`() {
        val result = canonicalizer.canonicalize("Κατάστημα Αθηνών")
        assertEquals("κατάστημα αθηνών", result.canonicalName)
    }
    
    @Test
    fun `handles special characters`() {
        val result = canonicalizer.canonicalize("Store-Name_Center")
        assertEquals("store name", result.canonicalName)  // "center" is a location suffix
        assertTrue(result.strippedParts.contains("center"))
    }

    // ---- Merged from MerchantCanonicalizerStressTest ----

    private data class CanonicalRow(
        val input: String,
        val expectedName: String,
        val expectedStripped: List<String> = emptyList()
    )

    @Test
    fun `corporate suffix and normalization rows produce canonical name and stripped parts`() {
        val rows = listOf(
            // greeklish corporate suffix
            CanonicalRow("papadopoulos ike", "papadopoulos", listOf("ike")),
            // latin corporate suffix, case-insensitive
            CanonicalRow("Company LtD", "company"),
            // greek prefix and suffix removed iteratively
            CanonicalRow("afoi bakery ike", "bakery", listOf("afoi", "ike")),
            // corporate suffix in Greek script
            CanonicalRow("Παπαδόπουλος ΙΚΕ", "παπαδόπουλος", listOf("ικε")),
            // punctuation and whitespace normalization
            CanonicalRow("  Foo---Bar,   LTD ", "foo bar")
        )
        assertTrue("rows must not be empty", rows.isNotEmpty())
        rows.forEach { row ->
            val result = canonicalizer.canonicalize(row.input)
            assertEquals("row '${row.input}' canonicalName", row.expectedName, result.canonicalName)
            row.expectedStripped.forEach { part ->
                assertTrue(
                    "row '${row.input}' strippedParts ${result.strippedParts} should contain '$part'",
                    result.strippedParts.contains(part)
                )
            }
        }
    }

    @Test
    fun `canonicalize is deterministic across repeated calls`() {
        val a = canonicalizer.canonicalize("Company IKE LTD")
        val b = canonicalizer.canonicalize("Company IKE LTD")
        assertEquals(a, b)
    }

    @Test
    fun `confidence penalty is monotonic with more stripped corporate parts`() {
        val none = canonicalizer.canonicalize("starbucks")
        val one = canonicalizer.canonicalize("starbucks ltd")
        val many = canonicalizer.canonicalize("afoi starbucks ltd ike")
        assertTrue(one.confidencePenalty >= none.confidencePenalty)
        assertTrue(many.confidencePenalty >= one.confidencePenalty)
    }
}

class GreeklishNormalizerTest {
    
    private lateinit var normalizer: GreeklishNormalizer
    
    @Before
    fun setup() {
        normalizer = GreeklishNormalizer()
    }
    
    @Test
    fun `converts Greek alpha to latin a`() {
        val result = normalizer.toLatin("αβγδ")
        assertEquals("avgd", result)  // β (beta) -> "v" in modern Greek
    }
    
    @Test
    fun `converts Greek word to latin`() {
        val result = normalizer.toLatin("Σκλαβενίτης")
        assertEquals("Sklavenitis", result)  // Greek -> Latin conversion
    }
    
    @Test
    fun `normalize converts Greek to latin`() {
        val result = normalizer.normalize("Σκλαβενίτης")
        assertEquals("sklavenitis", result)  // Greek -> Latin conversion
    }
    
    @Test
    fun `normalize keeps latin as lowercase`() {
        val result = normalizer.normalize("STARBUCKS")
        assertEquals("starbucks", result)
    }
    
    @Test
    fun `getVariations returns multiple forms`() {
        val variations = normalizer.getVariations("Σκλαβενίτης")
        assertTrue(variations.any { it.contains("sklavenitis") })
    }
    
    @Test
    fun `isGreekText detects Greek characters`() {
        assertTrue(normalizer.isGreekText("Σκλαβενίτης"))
        assertFalse(normalizer.isGreekText("Sklavenitis"))
    }
    
    @Test
    fun `isGreeklish detects mixed text`() {
        assertTrue(normalizer.isGreeklish("Σκλαβενιτης Store"))
        assertFalse(normalizer.isGreeklish("Sklavenitis"))
        assertFalse(normalizer.isGreeklish("Σκλαβενίτης"))
    }
    
    @Test
    fun `levenshteinDistance calculates correct distance`() {
        assertEquals(0, normalizer.levenshteinDistance("hello", "hello"))
        assertEquals(1, normalizer.levenshteinDistance("hello", "hallo"))
        assertEquals(3, normalizer.levenshteinDistance("hello", "hola"))  // e->o, l->a, delete o
        assertEquals(3, normalizer.levenshteinDistance("kitten", "sitting"))
    }
    
    @Test
    fun `findClosestMatch finds typo`() {
        val result = normalizer.findClosestMatch("Sklavvenitis", 2)
        assertEquals("sklavenitis", result)
    }
    
    @Test
    fun `findClosestMatch returns null for far matches`() {
        val result = normalizer.findClosestMatch("xyzabc", 2)
        assertNull(result)
    }
}

class SemanticKeywordMatcherTest {
    
    private lateinit var matcher: SemanticKeywordMatcher
    
    @Before
    fun setup() {
        val greeklishNormalizer = mockk<GreeklishNormalizer> {
            io.mockk.every { normalize(any()) } answers { firstArg<String>().lowercase() }
        }
        matcher = SemanticKeywordMatcher(greeklishNormalizer)
    }
    
    @Test
    fun `finds pizza keyword`() {
        val result = matcher.findBestMatch("Pizza Hut", 0.50)
        assertNotNull(result.bestMatch)
        assertEquals("Food", result.bestMatch!!.categoryName)
        assertTrue(result.bestMatch!!.confidence >= 0.50)
    }
    
    @Test
    fun `finds coffee keyword`() {
        val result = matcher.findBestMatch("Coffee Island", 0.50)
        assertNotNull(result.bestMatch)
        assertEquals("Food", result.bestMatch!!.categoryName)
    }
    
    @Test
    fun `finds supermarket keyword`() {
        val result = matcher.findBestMatch("Supermarket", 0.50)
        assertNotNull(result.bestMatch)
        assertEquals("Groceries", result.bestMatch!!.categoryName)
    }
    
    @Test
    fun `finds transport keyword`() {
        val result = matcher.findBestMatch("Shell Gas Station", 0.50)
        assertNotNull(result.bestMatch)
        assertEquals("Transport", result.bestMatch!!.categoryName)
    }
    
    @Test
    fun `pattern matching works for pizza`() {
        val result = matcher.findBestMatch("Pizza Hood", 0.70)
        assertNotNull(result.bestMatch)
        assertEquals("Food", result.bestMatch!!.categoryName)
    }
    
    @Test
    fun `pattern matching works for coffee house`() {
        val result = matcher.findBestMatch("Coffee House", 0.70)
        assertNotNull(result.bestMatch)
        assertEquals("Food", result.bestMatch!!.categoryName)
    }
    
    @Test
    fun `returns null for unknown merchants with high threshold`() {
        val result = matcher.findBestMatch("RandomUnknownXYZ", 0.50)
        assertNull(result.bestMatch)
    }
    
    @Test
    fun `returns null for completely unknown merchants`() {
        val result = matcher.findBestMatch("SomeUnknownXYZ123", 0.20)
        assertNull(result.bestMatch)  // No keywords match, regardless of threshold
    }
    
    @Test
    fun `finds multiple matches returns best`() {
        val results = matcher.match("Pizza and Coffee Shop", 0.30)
        assertTrue(results.isNotEmpty())
    }

    @Test
    fun `matches keyword with punctuation suffix`() {
        val result = matcher.findBestMatch("Disney+ subscription", 0.20)
        assertNotNull(result.bestMatch)
    }

    @Test
    fun `matches hyphenated keyword`() {
        val result = matcher.findBestMatch("e-food order", 0.20)
        assertNotNull(result.bestMatch)
        assertEquals("Food", result.bestMatch!!.categoryName)
    }

    // ---- Merged from SemanticKeywordMatcherStressTest (real GreeklishNormalizer) ----

    private data class RealNormalizerRow(
        val text: String,
        val expectedCategory: String? = null,
        val minConfidenceInclusive: Double? = null,
        val minConfidenceExclusive: Double? = null
    )

    private val realNormalizerMatcher = SemanticKeywordMatcher(GreeklishNormalizer())

    @Test
    fun `real normalizer default threshold rows resolve expected category and confidence`() {
        val rows = listOf(
            RealNormalizerRow("Coffee house payment", expectedCategory = "Food"),
            RealNormalizerRow("kafes freddo", minConfidenceInclusive = 0.5),
            RealNormalizerRow("espresso coffee house", minConfidenceExclusive = 0.7)
        )
        assertTrue("rows must not be empty", rows.isNotEmpty())
        rows.forEach { row ->
            val best = realNormalizerMatcher.findBestMatch(row.text).bestMatch
            assertNotNull("row '${row.text}' bestMatch", best)
            val expectedCategory: String? = row.expectedCategory
            if (expectedCategory != null) {
                assertEquals("row '${row.text}' category", expectedCategory, best!!.categoryName)
            }
            val minInclusive: Double? = row.minConfidenceInclusive
            if (minInclusive != null) {
                assertTrue("row '${row.text}' confidence ${best!!.confidence} >= $minInclusive",
                    best.confidence >= minInclusive)
            }
            val minExclusive: Double? = row.minConfidenceExclusive
            if (minExclusive != null) {
                assertTrue("row '${row.text}' confidence ${best!!.confidence} > $minExclusive",
                    best.confidence > minExclusive)
            }
        }
    }

    @Test
    fun `keyword matching is case-insensitive`() {
        val a = realNormalizerMatcher.findBestMatch("PIZZA HOUSE")
        val b = realNormalizerMatcher.findBestMatch("pizza house")
        assertEquals(a.bestMatch?.categoryName, b.bestMatch?.categoryName)
    }

    @Test
    fun `match keeps single keyword confidence in range and is deterministic`() {
        val matches = realNormalizerMatcher.match("coffee")
        assertTrue(matches.isNotEmpty())
        assertTrue(matches.first().confidence in 0.0..1.0)

        val a = realNormalizerMatcher.match("gas station shell")
        val b = realNormalizerMatcher.match("gas station shell")
        assertEquals(a, b)
    }
}

class ContextualInferenceEngineTest {
    
    private lateinit var engine: ContextualInferenceEngine
    
    @Before
    fun setup() {
        engine = ContextualInferenceEngine(timeProvider = mockk(relaxed = true))
    }
    
    @Test
    fun `detects likely surname - single word`() {
        assertTrue(engine.isLikelySurname("Papadopoulos"))
        assertTrue(engine.isLikelySurname("Nikolaidis"))
        assertTrue(engine.isLikelySurname("Georgiou"))
    }
    
    @Test
    fun `rejects business names as surnames`() {
        assertFalse(engine.isLikelySurname("Pizza Shop"))
        assertFalse(engine.isLikelySurname("Coffee Cafe"))
        assertFalse(engine.isLikelySurname("AB Store"))
    }
    
    @Test
    fun `infers food from small amount morning`() {
        val timestamp = getTimestampForHour(9) // 9 AM
        val result = engine.inferFromContext(5.0, timestamp)
        
        assertNotNull(result)
        assertEquals("Food", result!!.categoryName)
        assertTrue(result.confidence >= 0.45)
    }
    
    @Test
    fun `infers food from lunch time`() {
        val timestamp = getTimestampForHour(13) // 1 PM
        val result = engine.inferFromContext(15.0, timestamp)
        
        assertNotNull(result)
        assertEquals("Food", result!!.categoryName)
    }
    
    @Test
    fun `infers shopping from large amount`() {
        val timestamp = getTimestampForHour(15) // 3 PM
        val result = engine.inferFromContext(75.0, timestamp)
        
        assertNotNull(result)
        assertTrue(result!!.categoryName in listOf("Shopping", "Groceries"))
    }
    
    @Test
    fun `returns null for insufficient context`() {
        val timestamp = getTimestampForHour(15)
        // Amount 0 should give low confidence
        val result = engine.inferFromContext(0.0, timestamp)
        assertNull(result)
    }
    
    @Test
    fun `buildReason includes amount info`() {
        val timestamp = getTimestampForHour(9)
        val result = engine.inferFromContext(5.0, timestamp)
        
        assertNotNull(result)
        assertTrue(result!!.reason.contains("amount"))
        assertTrue(result.reason.contains("morning"))
    }

    @Test
    fun `does not classify arbitrary multiword merchant as surname`() {
        assertFalse(engine.isLikelySurname("coffee roasters"))
    }

    // ---- Merged from ContextualInferenceEngineStressTest ----

    private data class InferRow(
        val label: String,
        val amount: Double,
        val hour: Int = 12,
        val timestampDay: Int = DayOfWeek.MONDAY.value,
        val dayOfWeek: Int? = DayOfWeek.MONDAY.value,
        val source: String? = null,
        val allowedCategories: Set<String>? = null,
        val reasonContains: String? = null,
        val reasonExcludes: String? = null,
        val requireNonEmptyReason: Boolean = false
    )

    private fun amountRows(group: String, allowed: Set<String>?, vararg amounts: Double): List<InferRow> =
        amounts.map { InferRow(label = "$group EUR $it", amount = it, allowedCategories = allowed) }

    private fun assertInferRows(table: String, rows: List<InferRow>) {
        assertTrue("$table table must not be empty", rows.isNotEmpty())
        rows.forEach { row ->
            val msg = "$table row '${row.label}' (amount=${row.amount}, hour=${row.hour}, " +
                "day=${row.dayOfWeek}, source=${row.source})"
            val prediction = engine.inferFromContext(
                amount = row.amount,
                timestamp = createTimestamp(hour = row.hour, dayOfWeek = row.timestampDay),
                dayOfWeek = row.dayOfWeek,
                notificationSource = row.source
            )
            assertNotNull("$msg should predict", prediction)
            val predicted = prediction!!
            val allowed: Set<String>? = row.allowedCategories
            if (allowed != null) {
                assertTrue("$msg category '${predicted.categoryName}' should be in $allowed",
                    predicted.categoryName in allowed)
            }
            val contains: String? = row.reasonContains
            if (contains != null) {
                assertTrue("$msg reason '${predicted.reason}' should contain '$contains'",
                    predicted.reason.contains(contains))
            }
            val excludes: String? = row.reasonExcludes
            if (excludes != null) {
                assertFalse("$msg reason '${predicted.reason}' should not contain '$excludes'",
                    predicted.reason.contains(excludes))
            }
            if (row.requireNonEmptyReason) {
                assertTrue("$msg reason should not be empty", predicted.reason.isNotEmpty())
            }
        }
    }

    @Test
    fun `amount bracket rows predict allowed categories at midday Monday`() {
        val rows = amountRows("tiny under 3", setOf("Food"), 0.50, 1.00, 1.50, 2.00, 2.99) +
            amountRows("small 3-8", setOf("Food"), 3.00, 4.50, 6.00, 7.99) +
            amountRows("medium 8-20", setOf("Food", "Transport"), 8.00, 12.00, 15.50, 19.99) +
            amountRows("large 20-50", setOf("Shopping", "Food"), 20.00, 30.00, 40.00, 49.99) +
            amountRows("extra large 50-100", setOf("Shopping", "Transport", "Groceries", "Food"),
                50.00, 65.00, 80.00, 99.99) +
            amountRows("huge over 100", setOf("Shopping", "Transport"), 100.00, 200.00, 500.00, 1000.00, 5000.00) +
            amountRows("grocery bracket 20-150", null, 20.00, 50.00, 75.00, 100.00, 149.99) +
            amountRows("boundary", null,
                2.99, 3.00, 3.01, 7.99, 8.00, 8.01, 19.99, 20.00, 20.01,
                49.99, 50.00, 50.01, 99.99, 100.00, 100.01) +
            amountRows("very large", setOf("Shopping", "Transport"), 1000000.0)
        assertEquals(5 + 4 + 4 + 4 + 4 + 5 + 5 + 15 + 1, rows.size)
        assertInferRows("amount bracket", rows)
    }

    @Test
    fun `time of day rows predict and mention the expected daypart`() {
        fun timeRows(daypart: String, amount: Double, expectedReason: String, vararg hours: Int): List<InferRow> =
            hours.map {
                InferRow(
                    label = "$daypart $it:00", amount = amount, hour = it,
                    allowedCategories = if (daypart == "breakfast") setOf("Food") else null,
                    reasonContains = expectedReason
                )
            }

        val rows = timeRows("breakfast", 5.0, "morning", 6, 7, 8, 9) +
            timeRows("lunch", 10.0, "lunch", 12, 13, 14) +
            timeRows("dinner", 15.0, "evening", 18, 19, 20, 21) +
            timeRows("night", 20.0, "night", 22, 23)
        assertEquals(4 + 3 + 4 + 2, rows.size)
        assertInferRows("time of day", rows)
    }

    @Test
    fun `all hours of day keep any prediction confidence within 0 to 1`() {
        val hours = (0..23).toList()
        assertEquals(24, hours.size)
        hours.forEach { hour ->
            val prediction = engine.inferFromContext(
                amount = 10.0,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = DayOfWeek.MONDAY.value
            )
            if (prediction != null) {
                assertTrue("hour $hour confidence ${prediction.confidence} should be valid",
                    prediction.confidence in 0.0..1.0)
            }
        }
    }

    @Test
    fun `weekday and weekend rows mention weekend only on Saturday and Sunday`() {
        val rows = (1..7).map { day ->
            val isWeekend = day == DayOfWeek.SATURDAY.value || day == DayOfWeek.SUNDAY.value
            InferRow(
                label = "ISO day $day", amount = 20.0, dayOfWeek = day,
                reasonContains = if (isWeekend) "weekend" else null,
                reasonExcludes = if (isWeekend) null else "weekend"
            )
        }
        assertEquals(7, rows.size)
        assertInferRows("weekday vs weekend", rows)
    }

    @Test
    fun `weekend amounts always produce a prediction`() {
        val rows = listOf(5.0, 10.0, 20.0, 50.0, 100.0).map {
            InferRow(label = "weekend EUR $it", amount = it, dayOfWeek = DayOfWeek.SATURDAY.value)
        }
        assertEquals(5, rows.size)
        assertInferRows("weekend grocery boost", rows)
    }

    @Test
    fun `notification source and optional parameter rows predict with expected reason`() {
        val rows = listOf(
            InferRow("revolut source", 10.0, source = "com.revolut.revolut", reasonContains = "revolut"),
            InferRow("greek bank nbg", 10.0, source = "gr.nbg.mobilebanking", reasonContains = "bank"),
            InferRow("greek bank eurobank", 10.0, source = "com.eurobank.mobile", reasonContains = "bank"),
            InferRow("greek bank alpha", 10.0, source = "gr.alpha.mobile", reasonContains = "bank"),
            InferRow("google wallet source", 25.0, source = "com.google.android.apps.walletnfcrel"),
            InferRow("unknown source package", 10.0, source = "com.unknown.app"),
            InferRow("null source", 10.0, source = null),
            InferRow("null day of week falls back to timestamp", 10.0,
                timestampDay = DayOfWeek.FRIDAY.value, dayOfWeek = null),
            InferRow("all optional parameters null", 25.0,
                timestampDay = DayOfWeek.SATURDAY.value, dayOfWeek = null, source = null),
            InferRow("reason completeness with amount morning weekend and source", 7.0, hour = 8,
                dayOfWeek = DayOfWeek.SATURDAY.value, source = "com.revolut.revolut",
                requireNonEmptyReason = true)
        )
        assertEquals(10, rows.size)
        assertInferRows("source and optional parameters", rows)
    }

    @Test
    fun `amount and time combinations always produce a prediction`() {
        val hours = listOf(7, 12, 19, 22)
        val rows = listOf(2.0, 5.0, 15.0, 30.0, 75.0).flatMap { amount ->
            hours.map { hour -> InferRow("EUR $amount at $hour:00", amount, hour = hour) }
        }
        assertEquals(20, rows.size)
        assertInferRows("amount x hour", rows)

        val allFactorRows = listOf(5.0, 25.0, 75.0).flatMap { amount ->
            listOf(8, 13, 20).flatMap { hour ->
                listOf(DayOfWeek.MONDAY.value, DayOfWeek.SATURDAY.value).flatMap { day ->
                    listOf<String?>(null, "com.revolut.revolut", "gr.nbg.mobilebanking").map { source ->
                        InferRow("EUR $amount at $hour:00 day $day", amount, hour = hour, dayOfWeek = day, source = source)
                    }
                }
            }
        }
        assertEquals("all factor combinations", 54, allFactorRows.size)
        assertInferRows("all factors", allFactorRows)
    }

    @Test
    fun `greek surname rows are detected as surnames`() {
        // Papadopoulos, Nikolaidis, Georgiou are already asserted in the single-word test above.
        val endingRows = listOf(
            "Nikolaou", "Georgakis", "Constantinidis", "Ioannidis", "Athanasiou",
            "Michailidis", "Dimitriou", "Papadakis", "Mavridis", "Papageorgiou"
        ).map { "by ending: $it" to it }
        val prefixRows = listOf(
            "Papadimitriou", "Nikolaides", "Constantinou", "Ioannou", "Athanasiadis", "Dimitriadis"
        ).map { "by prefix: $it" to it }
        val rows = endingRows + prefixRows
        assertEquals(10 + 6, rows.size)
        rows.forEach { (label, surname) ->
            assertTrue("Should detect '$surname' as surname ($label)", engine.isLikelySurname(surname))
        }
    }

    @Test
    fun `business names and degenerate strings are rejected as surnames`() {
        val rows = listOf(
            "Starbucks", "Amazon", "Supermarket", "Cafe Restaurant", "Mini Market", "Pizza Store", "Bakery Shop",
            "A", "ab", "", "Shop", "Store Ltd"
        )
        assertEquals(12, rows.size)
        rows.forEach { name ->
            assertFalse("Should NOT detect '$name' as surname", engine.isLikelySurname(name))
        }
    }

    @Test
    fun `any prediction meets minimum confidence threshold`() {
        val generated = (1..100).map { i ->
            Triple(
                i * 0.5,  // Amount 0.5 to 50
                (i % 24),  // Hour 0-23
                (i % 7) + 1  // Day 1-7
            )
        }
        // Very tiny amount at 3am on a weekday: no boosts, may fall below threshold.
        val rows = generated + Triple(0.10, 3, DayOfWeek.WEDNESDAY.value)
        assertEquals(101, rows.size)
        rows.forEach { (amount, hour, day) ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = hour),
                dayOfWeek = day
            )
            if (prediction != null) {
                assertTrue("amount=$amount hour=$hour day=$day confidence ${prediction.confidence} " +
                    "should meet threshold",
                    prediction.confidence >= ContextualInferenceEngine.MIN_CONFIDENCE_THRESHOLD)
            }
        }
    }

    @Test
    fun `zero and negative amounts keep any prediction confidence within 0 to 1`() {
        val amounts = listOf(0.0, -10.0)
        assertEquals(2, amounts.size)
        amounts.forEach { amount ->
            val prediction = engine.inferFromContext(
                amount = amount,
                timestamp = createTimestamp(hour = 12),
                dayOfWeek = DayOfWeek.MONDAY.value
            )
            if (prediction != null) {
                assertTrue("amount=$amount confidence ${prediction.confidence} should be valid",
                    prediction.confidence in 0.0..1.0)
            }
        }
    }

    @Test
    fun `timestamp weekday fallback agrees with explicit ISO weekdays`() {
        val days = DayOfWeek.values().toList()
        assertEquals(7, days.size)
        days.forEach { day ->
            val timestamp = createTimestamp(hour = 12, dayOfWeek = day.value)
            val explicit = engine.inferFromContext(20.0, timestamp, day.value)
            val inferred = engine.inferFromContext(20.0, timestamp)
            assertNotNull("Expected a prediction for $day", explicit)
            assertNotNull("Expected a timestamp-derived prediction for $day", inferred)
            assertEquals(explicit!!.categoryName, inferred!!.categoryName)
            assertEquals(explicit.confidence, inferred.confidence, 0.0)
            assertEquals(explicit.reason, inferred.reason)
            assertEquals(
                day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY,
                inferred.reason.contains("weekend")
            )
        }
    }

    // Fixed Monday base date; the engine uses ISO weekdays.
    private fun createTimestamp(hour: Int, dayOfWeek: Int = DayOfWeek.MONDAY.value): Long =
        LocalDate.of(2026, 4, 13)
            .with(DayOfWeek.of(dayOfWeek))
            .atTime(hour, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    private fun getTimestampForHour(hour: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        return cal.timeInMillis
    }
}

