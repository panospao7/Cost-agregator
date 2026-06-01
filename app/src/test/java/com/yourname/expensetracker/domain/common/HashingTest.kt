package com.yourname.expensetracker.domain.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashingTest {

    @Test
    fun sha256_produces_64_char_hex_string() {
        val hash = "hello".sha256()
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun sha256_is_deterministic() {
        val input = "deterministic test input"
        val hash1 = input.sha256()
        val hash2 = input.sha256()
        assertEquals(hash1, hash2)
    }

    @Test
    fun sha256Fingerprint_is_identical_to_sha256() {
        val input = "fingerprint comparison"
        assertEquals(input.sha256(), input.sha256Fingerprint())
    }

    @Test
    fun sha256_matches_expected_hash() {
        // Known SHA-256 of "test"
        val expected = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(expected, "test".sha256())
    }
}
