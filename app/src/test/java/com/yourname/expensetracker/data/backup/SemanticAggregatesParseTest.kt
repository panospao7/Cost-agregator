package com.yourname.expensetracker.data.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

/**
 * UF-46 / S1: the manifest-section parser (INV-1, INV-12; AE-1, AE-6, AE-13).
 *
 * Every test builds its JSON by hand (never through the production serializer, except the dedicated
 * round-trip tests), so the structural rules are pinned independently of the writer. Each rejection test
 * is paired with a control that parses the same shape one step inside the boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesParseTest {

    private val hash = "0123456789abcdef"

    // ── Hand-built JSON ───────────────────────────────────────────

    private fun bucketJson(n: Any, u: Any, s: Any?, vararg key: String): JSONObject {
        val keyArray = JSONArray()
        key.forEach { keyArray.put(it) }
        return JSONObject().put("key", keyArray).put("n", n).put("u", u).put("s", s)
    }

    private fun entryJson(buckets: List<JSONObject>, definitionHash: Any? = hash): JSONObject {
        val array = JSONArray()
        buckets.forEach { array.put(it) }
        return JSONObject().put("definitionHash", definitionHash).put("buckets", array)
    }

    private fun validAggregates(): JSONObject = JSONObject()
        .put(
            "EXPENSE_RAW_BY_TYPE_CURRENCY",
            entryJson(listOf(bucketJson(2, 0, "52.5", "PURCHASE", "EUR")))
        )
        .put("SPENDING_EFFECTIVE_BY_CURRENCY", entryJson(listOf(bucketJson(2, 0, "32.5", "EUR"))))
        .put("INCOME_EFFECTIVE_BY_CURRENCY", entryJson(listOf(bucketJson(1, 0, "1000", "EUR"))))
        .put(
            "RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY",
            entryJson(listOf(bucketJson(1, 0, "9", "1", "EUR")))
        )

    private fun section(
        version: Any? = 1,
        status: Any? = "CAPTURED",
        aggregates: Any? = validAggregates(),
        reason: Any? = null
    ): JSONObject = JSONObject()
        .put("version", version)
        .put("status", status)
        .put("aggregates", aggregates)
        .put("notCapturedReason", reason)

    /** A valid CAPTURED section where one aggregate is replaced by [entry]. */
    private fun sectionWith(id: SemanticAggregateId, entry: JSONObject): JSONObject {
        val aggregates = validAggregates().put(id.name, entry)
        return section(aggregates = aggregates)
    }

    private fun parsed(json: JSONObject): SemanticAggregateSet {
        val result = SemanticAggregates.parse(json)
        assertTrue("expected Parsed but was $result", result is SemanticParseResult.Parsed)
        return (result as SemanticParseResult.Parsed).set
    }

    private fun assertMalformed(message: String, json: JSONObject) {
        val result = SemanticAggregates.parse(json)
        assertTrue("$message: expected Malformed but was $result", result is SemanticParseResult.Malformed)
        assertEquals(message, SemanticCode.SEMANTIC_MANIFEST_MALFORMED, (result as SemanticParseResult.Malformed).code)
    }

    private fun incomeBuckets(count: Int): List<JSONObject> =
        (0 until count).map { bucketJson(1, 0, "1", "C$it") }

    // ── Control and AE-1: hostile text is data or malformed, never SQL ────

    @Test
    fun parse_accepts_a_well_formed_captured_section_with_exact_values() {
        val set = parsed(section())

        assertEquals(SemanticAggregates.SECTION_VERSION, set.version)
        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        assertNull(set.notCapturedReason)
        assertEquals(SemanticAggregateId.values().toSet(), set.aggregates.keys)
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        assertEquals(hash, raw.definitionHash)
        val bucket = raw.buckets.single()
        assertEquals(listOf("PURCHASE", "EUR"), bucket.key)
        assertEquals(2, bucket.n)
        assertEquals(0, bucket.u)
        assertEquals(0, BigDecimal("52.5").compareTo(bucket.s))
        val recurring = set.aggregates.getValue(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY)
        assertEquals(listOf("1", "EUR"), recurring.buckets.single().key)
    }

    @Test
    fun parse_of_a_text_parsed_manifest_with_string_sums_is_accepted() {
        val text = """
            {"version":1,"status":"CAPTURED","aggregates":{
              "EXPENSE_RAW_BY_TYPE_CURRENCY":{"definitionHash":"h","buckets":[{"key":["PURCHASE","EUR"],"n":1,"u":0,"s":"12.5"}]},
              "SPENDING_EFFECTIVE_BY_CURRENCY":{"definitionHash":"h","buckets":[]},
              "INCOME_EFFECTIVE_BY_CURRENCY":{"definitionHash":"h","buckets":[]},
              "RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY":{"definitionHash":"h","buckets":[]}}}
        """.trimIndent()

        val set = parsed(JSONObject(text))

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        assertEquals(0, BigDecimal("12.5").compareTo(set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.single().s))
        // An empty bucket list is a legitimate (empty table) aggregate, not a malformed one.
        assertTrue(set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.isEmpty())
    }

    @Test
    fun parse_AE1_hostile_aggregate_ids_are_malformed() {
        // Control: the same shape with the registered ids parses.
        parsed(section())

        listOf("SELECT 1", "DROP TABLE x", "SELECT CAST(SUM(amount) AS TEXT) FROM expenses", "").forEach { hostileId ->
            val aggregates = validAggregates().put(hostileId, entryJson(emptyList()))
            assertMalformed("extra id '$hostileId' must be malformed", section(aggregates = aggregates))
        }
        // Replacing a registered id by a hostile one leaves a missing id: also malformed.
        val replaced = JSONObject()
        replaced.put("SELECT 1", entryJson(emptyList()))
        SemanticAggregateId.values().drop(1).forEach { replaced.put(it.name, entryJson(emptyList())) }
        assertMalformed("a hostile id in place of a registered one", section(aggregates = replaced))
    }

    @Test
    fun parse_AE1_hostile_text_in_keys_and_hash_is_kept_verbatim_as_inert_data() {
        val hostileKey = "'; DROP TABLE expenses; --"
        val hostileHash = "x'); DELETE FROM expenses; --"
        val json = sectionWith(
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
            entryJson(listOf(bucketJson(1, 0, "1", hostileKey)), definitionHash = hostileHash)
        )

        val entry = parsed(json).aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)

        assertEquals(hostileHash, entry.definitionHash)
        assertEquals(listOf(hostileKey), entry.buckets.single().key)
    }

    @Test
    fun parse_never_throws_for_structurally_hostile_input() {
        val hostileInputs = listOf(
            JSONObject(),
            section(aggregates = JSONArray().put(1)),
            section(aggregates = "SELECT 1"),
            section(aggregates = 7),
            section(status = JSONObject()),
            section(status = null),
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, JSONObject()),
            validAggregates().put("INCOME_EFFECTIVE_BY_CURRENCY", "text").let { section(aggregates = it) },
            validAggregates().put("INCOME_EFFECTIVE_BY_CURRENCY", JSONObject.NULL).let { section(aggregates = it) },
            sectionWith(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                JSONObject().put("definitionHash", hash).put("buckets", "not an array")
            ),
            sectionWith(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                JSONObject().put("definitionHash", hash).put("buckets", JSONArray().put("not an object"))
            )
        )
        // Every input is structurally invalid at the known version (or has no version), so each one must come
        // back as Malformed; an exception escaping parse() would fail the test.
        hostileInputs.forEachIndexed { index, json ->
            val result = SemanticAggregates.parse(json)
            assertTrue("input #$index must be Malformed but was $result", result is SemanticParseResult.Malformed)
        }
    }

    // ── AE-6: a numeric sum is rejected; serialization writes strings ────

    @Test
    fun parse_AE6_numeric_sum_is_malformed_and_a_string_sum_is_accepted() {
        // Control.
        parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1000", "EUR")))))

        assertMalformed(
            "double sum",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, 1000.5, "EUR"))))
        )
        assertMalformed(
            "int sum",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, 1000, "EUR"))))
        )
        assertMalformed(
            "long sum",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, 1000L, "EUR"))))
        )
        assertMalformed(
            "missing sum",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, null, "EUR"))))
        )
    }

    @Test
    fun parse_AE6_numeric_sum_in_manifest_text_is_malformed() {
        val text = """
            {"version":1,"status":"CAPTURED","aggregates":{
              "EXPENSE_RAW_BY_TYPE_CURRENCY":{"definitionHash":"h","buckets":[]},
              "SPENDING_EFFECTIVE_BY_CURRENCY":{"definitionHash":"h","buckets":[]},
              "INCOME_EFFECTIVE_BY_CURRENCY":{"definitionHash":"h","buckets":[{"key":["EUR"],"n":1,"u":0,"s":1000.0}]},
              "RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY":{"definitionHash":"h","buckets":[]}}}
        """.trimIndent()

        assertMalformed("a JSON number for s", JSONObject(text))
    }

    @Test
    fun toJson_writes_every_sum_as_a_json_string_and_parse_restores_the_exact_value() {
        val exact = BigDecimal(9.99) // exact binary expansion, never rounded
        val set = SemanticAggregateSet.ofCaptured(
            SemanticAggregateId.values().associateWith { id ->
                AggregateEntry(
                    hash,
                    listOf(
                        AggregateBucket(List(if (id == SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY || id == SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY) 2 else 1) { "k$it" }, 3, 1, exact)
                    )
                )
            }
        )

        val json = set.toJson()

        val aggregates = json.getJSONObject("aggregates")
        SemanticAggregateId.values().forEach { id ->
            val value = aggregates.getJSONObject(id.name).getJSONArray("buckets").getJSONObject(0).get("s")
            assertTrue("s of $id must be a JSON string but was ${value.javaClass}", value is String)
            assertEquals(exact.toPlainString(), value)
        }
        val restored = parsed(json)
        assertEquals(set, restored)
        assertEquals(
            0,
            exact.compareTo(restored.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.single().s)
        )
    }

    @Test
    fun toJson_and_parse_round_trip_a_not_captured_section_for_every_reason() {
        SemanticNotCapturedReason.values().forEach { reason ->
            val set = SemanticAggregateSet.ofNotCaptured(reason)

            val restored = parsed(set.toJson())

            assertEquals(set, restored)
            assertEquals(SemanticCaptureStatus.NOT_CAPTURED, restored.status)
            assertEquals(reason, restored.notCapturedReason)
            assertTrue(restored.aggregates.isEmpty())
        }
    }

    @Test
    fun parsed_sum_text_with_different_scale_is_equal_to_the_same_value() {
        val a = parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "32.5", "EUR")))))
        val b = parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "32.50", "EUR")))))
        val c = parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "32.51", "EUR")))))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }

    // ── AE-13: structural rejection at a known version ────────────

    @Test
    fun parse_AE13_negative_n_or_u_is_malformed() {
        parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(0, 0, "0", "EUR")))))

        assertMalformed("n=-1", sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(-1, 0, "0", "EUR")))))
        assertMalformed("u=-1", sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, -1, "0", "EUR")))))
    }

    @Test
    fun parse_AE13_non_integer_counts_are_malformed() {
        listOf<Any>("1", 1.5, 3_000_000_000L, JSONObject.NULL, true).forEach { bad ->
            assertMalformed(
                "n=$bad",
                sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(bad, 0, "0", "EUR"))))
            )
            assertMalformed(
                "u=$bad",
                sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, bad, "0", "EUR"))))
            )
        }
    }

    @Test
    fun parse_AE13_duplicate_key_is_malformed_but_case_differences_are_distinct_keys() {
        // Control: keys that differ only by case are different (comparison is case-sensitive).
        parsed(
            sectionWith(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                entryJson(listOf(bucketJson(1, 0, "1", "EUR"), bucketJson(1, 0, "1", "eur")))
            )
        )

        assertMalformed(
            "duplicate key",
            sectionWith(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                entryJson(listOf(bucketJson(1, 0, "1", "EUR"), bucketJson(2, 0, "2", "EUR")))
            )
        )
        assertMalformed(
            "duplicate two-element key",
            sectionWith(
                SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                entryJson(listOf(bucketJson(1, 0, "1", "PURCHASE", "EUR"), bucketJson(1, 0, "1", "PURCHASE", "EUR")))
            )
        )
    }

    @Test
    fun parse_AE13_bucket_limit_is_256_per_aggregate() {
        val atLimit = parsed(
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(incomeBuckets(256)))
        )
        assertEquals(256, atLimit.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.size)

        assertMalformed(
            "257 buckets",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(incomeBuckets(257)))
        )
        assertEquals(256, SemanticAggregates.MAX_BUCKETS_PER_AGGREGATE)
    }

    @Test
    fun parse_AE13_captured_without_exactly_the_four_ids_is_malformed() {
        // Control: all four.
        parsed(section())

        SemanticAggregateId.values().forEach { missing ->
            val aggregates = JSONObject()
            SemanticAggregateId.values().filter { it != missing }.forEach { aggregates.put(it.name, entryJson(emptyList())) }
            assertMalformed("CAPTURED without $missing", section(aggregates = aggregates))
        }
        assertMalformed("CAPTURED with empty aggregates", section(aggregates = JSONObject()))
        assertMalformed("CAPTURED with null aggregates", section(aggregates = null))
        assertMalformed("CAPTURED with JSON null aggregates", section(aggregates = JSONObject.NULL))
    }

    @Test
    fun parse_AE13_not_captured_with_aggregates_present_is_malformed() {
        assertMalformed(
            "NOT_CAPTURED with all four ids",
            section(status = "NOT_CAPTURED", reason = "SEMANTIC_CAPTURE_FAILED")
        )
        assertMalformed(
            "NOT_CAPTURED with one id",
            section(
                status = "NOT_CAPTURED",
                aggregates = JSONObject().put("INCOME_EFFECTIVE_BY_CURRENCY", entryJson(emptyList())),
                reason = "SEMANTIC_CAPTURE_FAILED"
            )
        )
    }

    @Test
    fun parse_AE13_not_captured_without_aggregates_is_accepted_with_a_tolerant_reason() {
        // Control for the rejection above: empty, absent or null aggregates are fine.
        listOf<Any?>(JSONObject(), null, JSONObject.NULL).forEach { aggregates ->
            val set = parsed(section(status = "NOT_CAPTURED", aggregates = aggregates, reason = "SEMANTIC_BUCKET_LIMIT"))
            assertEquals(SemanticCaptureStatus.NOT_CAPTURED, set.status)
            assertEquals(SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT, set.notCapturedReason)
            assertTrue(set.aggregates.isEmpty())
        }
        // An unknown or missing reason is tolerated (restore is NOT_VERIFIABLE either way, INV-11).
        listOf<Any?>("SOMETHING_ELSE", null, 7, "SELECT 1").forEach { reason ->
            val set = parsed(section(status = "NOT_CAPTURED", aggregates = null, reason = reason))
            assertEquals(SemanticCaptureStatus.NOT_CAPTURED, set.status)
            assertNull(set.notCapturedReason)
            assertTrue(set.aggregates.isEmpty())
        }
    }

    @Test
    fun parse_AE13_unknown_version_is_parsed_but_never_interpreted() {
        listOf(0, 2, 99, -1, Int.MAX_VALUE).forEach { version ->
            // The content would be malformed at version 1; at an unknown version it is not even read.
            val set = parsed(
                section(version = version, status = "WHATEVER", aggregates = "SELECT 1").put("extra", JSONObject())
            )
            assertEquals(version, set.version)
            assertTrue("unknown version $version must carry no aggregates", set.aggregates.isEmpty())
            assertFalse(version == SemanticAggregates.SECTION_VERSION)
        }
        // A valid-looking body at version 99 is still not interpreted.
        val set = parsed(section(version = 99))
        assertTrue(set.aggregates.isEmpty())
    }

    @Test
    fun parse_AE13_missing_or_mistyped_version_and_status_are_malformed() {
        assertMalformed("empty object", JSONObject())
        assertMalformed("no version", section(version = null))
        assertMalformed("string version", section(version = "1"))
        assertMalformed("double version", section(version = 1.5))
        assertMalformed("no status", section(status = null))
        assertMalformed("unknown status", section(status = "VERIFIED"))
        assertMalformed("lower-case status", section(status = "captured"))
        assertMalformed("numeric status", section(status = 1))
    }

    @Test
    fun parse_AE13_wrong_key_arity_is_malformed() {
        // Two-key aggregates need two elements; one-key aggregates need one.
        assertMalformed(
            "EXPENSE_RAW with one key element",
            sectionWith(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", "PURCHASE"))))
        )
        assertMalformed(
            "EXPENSE_RAW with three key elements",
            sectionWith(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", "PURCHASE", "EUR", "x"))))
        )
        assertMalformed(
            "RECURRING with one key element",
            sectionWith(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", "1"))))
        )
        assertMalformed(
            "INCOME with two key elements",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", "EUR", "x"))))
        )
        assertMalformed(
            "INCOME with an empty key",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1"))))
        )
    }

    @Test
    fun parse_AE13_key_elements_must_be_strings_of_at_most_64_characters() {
        val atLimit = "A".repeat(SemanticAggregates.MAX_KEY_ELEMENT_LENGTH)
        val entry = parsed(
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", atLimit))))
        ).aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)
        assertEquals(listOf(atLimit), entry.buckets.single().key)

        assertMalformed(
            "65-character key element",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, "1", atLimit + "A"))))
        )
        val numericKey = JSONObject().put("key", JSONArray().put(5)).put("n", 1).put("u", 0).put("s", "1")
        assertMalformed(
            "numeric key element",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(numericKey)))
        )
        val nullKey = JSONObject().put("key", JSONArray().put(JSONObject.NULL)).put("n", 1).put("u", 0).put("s", "1")
        assertMalformed(
            "null key element",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(nullKey)))
        )
    }

    @Test
    fun parse_AE13_sum_text_is_limited_to_2048_characters() {
        val atLimit = "1".repeat(SemanticAggregates.MAX_SUM_TEXT_LENGTH)
        val set = parsed(
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, atLimit, "EUR"))))
        )
        assertEquals(0, BigDecimal(atLimit).compareTo(set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.single().s))

        assertMalformed(
            "2049-character sum",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, atLimit + "1", "EUR"))))
        )
    }

    @Test
    fun parse_AE13_sum_text_must_be_plain_decimal() {
        listOf("0", "-0", "12.5", "-12.50", "1000", "0.1000000000000000055511151231257827021181583404541015625").forEach { ok ->
            val set = parsed(
                sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, ok, "EUR"))))
            )
            assertEquals(
                ok,
                0,
                BigDecimal(ok).compareTo(set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.single().s)
            )
        }
        listOf("", " ", "-", "+1", "1e5", "1E5", "NaN", "Infinity", "-Infinity", ".5", "1.", "1.2.3", "--1", " 1", "1 ", "0x10", "1,5", "12٣")
            .forEach { bad ->
                assertMalformed(
                    "sum text '$bad'",
                    sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(listOf(bucketJson(1, 0, bad, "EUR"))))
                )
            }
    }

    @Test
    fun parse_AE13_definition_hash_must_be_a_non_empty_string_of_at_most_64_characters() {
        parsed(sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(emptyList(), definitionHash = "a".repeat(64))))

        assertMalformed(
            "65-character hash",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(emptyList(), definitionHash = "a".repeat(65)))
        )
        assertMalformed(
            "empty hash",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(emptyList(), definitionHash = ""))
        )
        assertMalformed(
            "missing hash",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(emptyList(), definitionHash = null))
        )
        assertMalformed(
            "numeric hash",
            sectionWith(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, entryJson(emptyList(), definitionHash = 12345))
        )
    }

    @Test
    fun parse_malformed_result_carries_only_the_bounded_malformed_code() {
        val result = SemanticAggregates.parse(JSONObject()) as SemanticParseResult.Malformed

        assertEquals(SemanticCode.SEMANTIC_MANIFEST_MALFORMED, result.code)
        assertEquals("SEMANTIC_MANIFEST_MALFORMED", result.code.name)
    }
}
