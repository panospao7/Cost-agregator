package com.yourname.expensetracker.domain.intelligence.ml

import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    private val extractor = FeatureExtractor()

    @Test
    fun `tokenize keeps useful tokens from special character merchants`() {
        val tokens = extractor.tokenize("H&M / 7-Eleven - AT&T")

        assertTrue(tokens.contains("eleven"))
    }
}
