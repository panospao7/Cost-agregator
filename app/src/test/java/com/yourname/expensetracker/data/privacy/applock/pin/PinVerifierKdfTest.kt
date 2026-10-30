package com.yourname.expensetracker.data.privacy.applock.pin

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S24 design decision 1: the verifier record format, strict parse, KDF determinism and the tag.
 * Pure JVM crypto. Derivations that do not need the production cost use the minimum accepted
 * iteration count; the production constant is only pinned by value.
 */
class PinVerifierKdfTest {

    private val saltHex = "ab".repeat(PinVerifierCrypto.SALT_BYTES)
    private val tagHex = "cd".repeat(PinVerifierCrypto.TAG_BYTES)
    private val pin = "482915".toCharArray()
    private val hmacKey = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "HmacSHA256")

    private fun raw(
        version: String = "1",
        alg: String = "1",
        iterations: String = "150000",
        salt: String = saltHex,
        aliasVersion: String = "1",
        tag: String = tagHex
    ): String = listOf(version, alg, iterations, salt, aliasVersion, tag).joinToString(":")

    // ---- format ----

    @Test
    fun record_format_round_trips_and_has_the_six_field_layout() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val tag = ByteArray(32) { (255 - it).toByte() }
        val record = PinVerifierRecord(1, 1, 150_000, salt, 1, tag)

        val text = record.serialize()

        assertEquals("1:1:150000:${PinVerifierCrypto.toHex(salt)}:1:${PinVerifierCrypto.toHex(tag)}", text)
        val parsed = PinVerifierRecord.parse(text)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.recordVersion)
        assertEquals(1, parsed.algId)
        assertEquals(150_000, parsed.iterations)
        assertArrayEquals(salt, parsed.salt)
        assertEquals(1, parsed.keyAliasVersion)
        assertArrayEquals(tag, parsed.tag)
    }

    @Test
    fun current_parameters_are_pinned() {
        assertEquals(150_000, PinVerifierCrypto.CURRENT_ITERATIONS)
        assertEquals(1, PinVerifierCrypto.CURRENT_ALG_ID)
        assertEquals(16, PinVerifierCrypto.SALT_BYTES)
        assertEquals(32, PinVerifierCrypto.OUTPUT_BYTES)
        assertEquals(50_000, PinVerifierCrypto.MIN_ITERATIONS)
        assertEquals(2_000_000, PinVerifierCrypto.MAX_ITERATIONS)
    }

    @Test
    fun hex_round_trips_every_byte_value() {
        val all = ByteArray(256) { it.toByte() }

        assertArrayEquals(all, PinVerifierCrypto.fromHex(PinVerifierCrypto.toHex(all)))
    }

    // ---- strict parse ----

    @Test
    fun parse_accepts_the_inclusive_iteration_bounds() {
        assertNotNull(PinVerifierRecord.parse(raw(iterations = "50000")))
        assertNotNull(PinVerifierRecord.parse(raw(iterations = "2000000")))
        assertNotNull(PinVerifierRecord.parse(raw()))
    }

    @Test
    fun parse_rejects_iterations_outside_the_accepted_range() {
        assertNull(PinVerifierRecord.parse(raw(iterations = "49999")))
        assertNull(PinVerifierRecord.parse(raw(iterations = "2000001")))
        assertNull(PinVerifierRecord.parse(raw(iterations = "0")))
        assertNull(PinVerifierRecord.parse(raw(iterations = "-150000")))
        assertNull(PinVerifierRecord.parse(raw(iterations = "abc")))
        assertNull(PinVerifierRecord.parse(raw(iterations = "")))
    }

    @Test
    fun parse_rejects_unknown_algorithm_record_version_and_alias_version() {
        assertNull(PinVerifierRecord.parse(raw(alg = "2")))
        assertNull(PinVerifierRecord.parse(raw(alg = "0")))
        assertNull(PinVerifierRecord.parse(raw(version = "2")))
        assertNull(PinVerifierRecord.parse(raw(aliasVersion = "2")))
        assertNull(PinVerifierRecord.parse(raw(alg = "x")))
    }

    @Test
    fun parse_rejects_wrong_salt_and_tag_lengths() {
        assertNull(PinVerifierRecord.parse(raw(salt = "ab".repeat(15))))
        assertNull(PinVerifierRecord.parse(raw(salt = "ab".repeat(17))))
        assertNull(PinVerifierRecord.parse(raw(salt = "")))
        assertNull(PinVerifierRecord.parse(raw(tag = "cd".repeat(31))))
        assertNull(PinVerifierRecord.parse(raw(tag = "cd".repeat(33))))
        assertNull(PinVerifierRecord.parse(raw(tag = "")))
    }

    @Test
    fun parse_rejects_non_hex_odd_length_and_wrong_field_counts() {
        assertNull(PinVerifierRecord.parse(raw(salt = "zz".repeat(16))))
        assertNull(PinVerifierRecord.parse(raw(tag = "c".repeat(63))))
        assertNull(PinVerifierRecord.parse(""))
        assertNull(PinVerifierRecord.parse("garbage"))
        assertNull(PinVerifierRecord.parse(raw().substringBeforeLast(":")))
        assertNull(PinVerifierRecord.parse(raw() + ":extra"))
    }

    @Test
    fun record_with_an_outdated_iteration_count_asks_for_rehash_and_the_current_one_does_not() {
        val salt = ByteArray(16)
        val tag = ByteArray(32)

        assertTrue(PinVerifierRecord(1, 1, 100_000, salt, 1, tag).isOutdated())
        assertFalse(PinVerifierRecord(1, 1, 150_000, salt, 1, tag).isOutdated())
        assertFalse(PinVerifierRecord(1, 1, 200_000, salt, 1, tag).isOutdated())
        assertTrue(PinVerifierRecord(1, 2, 150_000, salt, 1, tag).isOutdated())
    }

    @Test
    fun wipe_zero_fills_the_salt_and_tag() {
        val record = PinVerifierRecord(1, 1, 150_000, ByteArray(16) { 9 }, 1, ByteArray(32) { 9 })

        record.wipe()

        assertTrue(record.salt.all { it == 0.toByte() })
        assertTrue(record.tag.all { it == 0.toByte() })
    }

    // ---- KDF and tag ----

    @Test
    fun same_pin_and_salt_derive_the_same_output_and_a_different_salt_or_pin_does_not() {
        val iterations = PinVerifierCrypto.MIN_ITERATIONS
        val saltA = ByteArray(16) { 1 }
        val saltB = ByteArray(16) { 2 }

        val first = PinVerifierCrypto.derive(pin, saltA, iterations)
        val again = PinVerifierCrypto.derive(pin.copyOf(), saltA, iterations)
        val otherSalt = PinVerifierCrypto.derive(pin, saltB, iterations)
        val otherPin = PinVerifierCrypto.derive("482916".toCharArray(), saltA, iterations)

        assertEquals(PinVerifierCrypto.OUTPUT_BYTES, first.size)
        assertArrayEquals(first, again)
        assertFalse(first.contentEquals(otherSalt))
        assertFalse(first.contentEquals(otherPin))
        assertEquals("482915", String(pin))
    }

    @Test
    fun tag_is_deterministic_and_changes_with_every_input() {
        val iterations = PinVerifierCrypto.MIN_ITERATIONS
        val salt = ByteArray(16) { 3 }
        val derived = PinVerifierCrypto.derive(pin, salt, iterations)
        fun tag(key: SecretKeySpec = hmacKey, it: Int = iterations, s: ByteArray = salt, d: ByteArray = derived) =
            PinVerifierCrypto.computeTag(PinVerifierCrypto.newMac(key), it, s, d)

        val base = tag()

        assertEquals(PinVerifierCrypto.TAG_BYTES, base.size)
        assertArrayEquals(base, tag())
        assertFalse(base.contentEquals(tag(it = iterations + 1)))
        assertFalse(base.contentEquals(tag(s = ByteArray(16) { 4 })))
        assertFalse(base.contentEquals(tag(d = ByteArray(32) { 5 })))
        assertFalse(base.contentEquals(tag(key = SecretKeySpec(ByteArray(32) { 99 }, "HmacSHA256"))))
    }

    @Test
    fun constant_time_compare_is_correct_for_equal_unequal_and_different_length_inputs() {
        val a = ByteArray(32) { it.toByte() }

        assertTrue(PinVerifierCrypto.constantTimeEquals(a, a.copyOf()))
        assertFalse(PinVerifierCrypto.constantTimeEquals(a, a.copyOf().also { it[31] = 99 }))
        assertFalse(PinVerifierCrypto.constantTimeEquals(a, a.copyOf().also { it[0] = 99 }))
        assertFalse(PinVerifierCrypto.constantTimeEquals(a, a.copyOf(31)))
    }
}
