package com.yourname.expensetracker.domain.categorization

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import io.mockk.mockk

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
        assertEquals("ab", result.canonicalName)
        assertTrue(result.strippedParts.contains("sa"))
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
    
    private fun getTimestampForHour(hour: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        return cal.timeInMillis
    }
}

