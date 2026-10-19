package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Table-driven pin of the LIVE raw-text sanitizers in [RawContentSanitizer].
 *
 * Production callers (verified by search of app/src/main):
 *  - Called by live writers: sanitizeRawOcr (ReceiptRepository, ReceiptLifecycleCoordinator,
 *    BankStatementLifecycleProcessor), sanitizeNotificationText (NotificationProcessingPipeline),
 *    sanitizeEmailSender / sanitizeEmailSubject (ReceiptLifecycleCoordinator).
 *  - Reached only through the dead `*PersistencePayload` builders: sanitizedOcrReviewSnippet
 *    (ReceiptPersistencePayload).
 *  - NO production caller at all: sanitizeRawOcrNullable, sanitizeEmailMessageId,
 *    sanitizeEmailMessageIdWithHash, sanitizeNotificationExtras. They are pinned here as public
 *    API contract so a future caller cannot silently inherit a changed privacy behaviour.
 *
 * Covered: sanitizeRawOcr, sanitizeRawOcrNullable, sanitizeEmailSubject, sanitizeEmailSender,
 * sanitizeEmailMessageId, sanitizeEmailMessageIdWithHash, sanitizedOcrReviewSnippet,
 * sanitizeNotificationText, sanitizeNotificationExtras.
 * Not duplicated here: sanitizeBankDescription (see RawStoragePayloadMatrixTest).
 * [public_api_is_exactly_the_covered_functions_plus_bank_description] fails when a public
 * function is added to or removed from [RawContentSanitizer] without updating this table.
 *
 * CURRENT BEHAVIOURS ARE PINNED AS-IS. Expected values are written as literals (read from the
 * contract of each function), not recomputed. A change to any row is a deliberate privacy
 * behaviour change and must be reviewed as such.
 *
 * KNOWN INCONSISTENCY — see release follow-up R2:
 *  - [RawContentSanitizer.sanitizeNotificationExtras] returns null for STORE_REDACTED while live
 *    writers store `{"redacted":true}` for that mode.
 *  - [RawContentSanitizer.sanitizeEmailSender] / [RawContentSanitizer.sanitizeEmailSubject] /
 *    [RawContentSanitizer.sanitizeEmailMessageId] / [RawContentSanitizer.sanitizeEmailMessageIdWithHash]
 *    map a null input to "[REDACTED]" under STORE_REDACTED, and the non-null-returning
 *    [RawContentSanitizer.sanitizeRawOcr] does so too (the null-aware sanitizers
 *    sanitizeRawOcrNullable / sanitizeNotificationText keep null as null).
 *  These are pinned as they behave today; production is intentionally not "fixed" here.
 */
class RawContentSanitizerLiveTest {

    private val hashService = DefaultSensitiveHashingService()

    /** Purpose string EmailReceiptIngestionService uses for message-id hashes in production. */
    private companion object {
        const val EMAIL_MESSAGE_ID_PURPOSE = "emailMessageId"
    }

    private data class Row(
        val fn: String,
        val mode: RawStorageMode,
        val kind: String,
        val input: String?,
        val expected: String?,
        val hash: String? = null,
        /** Sentinel that must not appear in the output when mode is not STORE_RAW. */
        val sentinel: String? = null,
    ) {
        val id: String get() = "$fn/${mode.name}/$kind"
    }

    private fun sentinel(fn: String, mode: RawStorageMode) = "SECRET_${fn}_${mode.name}_XYZ"

    private val allFunctions = listOf(
        "sanitizeRawOcr",
        "sanitizeRawOcrNullable",
        "sanitizeEmailSubject",
        "sanitizeEmailSender",
        "sanitizeEmailMessageId",
        "sanitizeEmailMessageIdWithHash",
        "sanitizedOcrReviewSnippet",
        "sanitizeNotificationText",
        "sanitizeNotificationExtras",
    )

    /** Functions whose first parameter is nullable. */
    private val nullableInputFunctions = allFunctions - "sanitizedOcrReviewSnippet"

    private fun invoke(row: Row): String? = when (row.fn) {
        "sanitizeRawOcr" -> RawContentSanitizer.sanitizeRawOcr(row.input, row.mode)
        "sanitizeRawOcrNullable" -> RawContentSanitizer.sanitizeRawOcrNullable(row.input, row.mode)
        "sanitizeEmailSubject" -> RawContentSanitizer.sanitizeEmailSubject(row.input, row.mode)
        "sanitizeEmailSender" -> RawContentSanitizer.sanitizeEmailSender(row.input, row.mode)
        "sanitizeEmailMessageId" -> RawContentSanitizer.sanitizeEmailMessageId(row.input, row.mode)
        "sanitizeEmailMessageIdWithHash" ->
            RawContentSanitizer.sanitizeEmailMessageIdWithHash(row.input, row.hash, row.mode)
        "sanitizedOcrReviewSnippet" ->
            RawContentSanitizer.sanitizedOcrReviewSnippet(requireNotNull(row.input), row.mode)
        "sanitizeNotificationText" -> RawContentSanitizer.sanitizeNotificationText(row.input, row.mode)
        "sanitizeNotificationExtras" -> RawContentSanitizer.sanitizeNotificationExtras(row.input, row.mode)
        else -> error("unknown fn ${row.fn}")
    }

    /** One sentinel row per mode; STORE_RAW expects exact pass-through of the sentinel. */
    private fun sentinelRows(
        fn: String,
        redacted: String?,
        metadataOnly: String?,
        doNotStore: String?,
    ): List<Row> = RawStorageMode.entries.map { mode ->
        val s = sentinel(fn, mode)
        val expected = when (mode) {
            RawStorageMode.STORE_RAW -> s
            RawStorageMode.STORE_REDACTED -> redacted
            RawStorageMode.STORE_METADATA_ONLY -> metadataOnly
            RawStorageMode.DO_NOT_STORE -> doNotStore
        }
        Row(fn, mode, "sentinel", s, expected, sentinel = s)
    }

    private fun withHashSentinelRows(): List<Row> = RawStorageMode.entries.map { mode ->
        val fn = "sanitizeEmailMessageIdWithHash"
        val s = sentinel(fn, mode)
        // Real hashing service: the hash is the dedup token supplied by the caller.
        val hash = requireNotNull(hashService.hmacSha256Prefix(s, EMAIL_MESSAGE_ID_PURPOSE))
        val expected = when (mode) {
            RawStorageMode.STORE_RAW -> s
            RawStorageMode.STORE_REDACTED -> "[REDACTED]"
            RawStorageMode.STORE_METADATA_ONLY -> hash
            RawStorageMode.DO_NOT_STORE -> null
        }
        Row(fn, mode, "sentinel", s, expected, hash = hash, sentinel = s)
    }

    /** Sentinel-bearing rows: exactly one per (function, mode). */
    private val baseRows: List<Row> =
        sentinelRows("sanitizeRawOcr", redacted = "[REDACTED]", metadataOnly = "", doNotStore = "") +
            sentinelRows("sanitizeRawOcrNullable", "[REDACTED]", null, null) +
            sentinelRows("sanitizeEmailSubject", "[REDACTED]", null, null) +
            sentinelRows("sanitizeEmailSender", "[REDACTED]", null, null) +
            sentinelRows("sanitizeEmailMessageId", "[REDACTED]", null, null) +
            withHashSentinelRows() +
            sentinelRows(
                "sanitizedOcrReviewSnippet",
                redacted = "[REDACTED]",
                metadataOnly = "Receipt OCR captured; raw text storage disabled.",
                doNotStore = "Receipt OCR captured; raw text not stored.",
            ) +
            sentinelRows("sanitizeNotificationText", "[REDACTED]", null, null) +
            // KNOWN INCONSISTENCY — see release follow-up R2: REDACTED yields null here,
            // while live writers store {"redacted":true} for STORE_REDACTED.
            sentinelRows("sanitizeNotificationExtras", redacted = null, metadataOnly = null, doNotStore = null)

    private fun nullRow(fn: String, mode: RawStorageMode, expected: String?, hash: String? = null) =
        Row(fn, mode, "null-input", null, expected, hash = hash)

    private fun nullRows(fn: String, red: String?, meta: String?, dns: String?, raw: String?): List<Row> =
        listOf(
            nullRow(fn, RawStorageMode.STORE_RAW, raw),
            nullRow(fn, RawStorageMode.STORE_REDACTED, red),
            nullRow(fn, RawStorageMode.STORE_METADATA_ONLY, meta),
            nullRow(fn, RawStorageMode.DO_NOT_STORE, dns),
        )

    /** Null-input rows: exactly one per (nullable-input function, mode). */
    private val nullInputRows: List<Row> =
        // Under STORE_RAW the non-null overload turns null into "" (documented in KDoc).
        nullRows("sanitizeRawOcr", red = "[REDACTED]", meta = "", dns = "", raw = "") +
            nullRows("sanitizeRawOcrNullable", red = null, meta = null, dns = null, raw = null) +
            // KNOWN INCONSISTENCY — see release follow-up R2: null subject/sender -> "[REDACTED]".
            nullRows("sanitizeEmailSubject", red = "[REDACTED]", meta = null, dns = null, raw = null) +
            nullRows("sanitizeEmailSender", red = "[REDACTED]", meta = null, dns = null, raw = null) +
            nullRows("sanitizeEmailMessageId", red = "[REDACTED]", meta = null, dns = null, raw = null) +
            nullRows("sanitizeEmailMessageIdWithHash", red = "[REDACTED]", meta = null, dns = null, raw = null) +
            nullRows("sanitizeNotificationText", red = null, meta = null, dns = null, raw = null) +
            nullRows("sanitizeNotificationExtras", red = null, meta = null, dns = null, raw = null)

    private val capLong200 = "B".repeat(200)
    private val capLong201 = "B".repeat(200) + "C"
    private val snippetSentinelPad: String = sentinel("sanitizedOcrReviewSnippet", RawStorageMode.STORE_RAW)
        .let { it + "A".repeat(300) }

    /** Boundary rows (empty string, length cap, hash with null message id). */
    private val extraRows: List<Row> = listOf(
        // Empty string is "data cleared", distinct from null.
        Row("sanitizeRawOcr", RawStorageMode.STORE_RAW, "empty", "", ""),
        Row("sanitizeRawOcr", RawStorageMode.STORE_REDACTED, "empty", "", "[REDACTED]"),
        Row("sanitizeRawOcrNullable", RawStorageMode.STORE_RAW, "empty", "", ""),
        Row("sanitizeRawOcrNullable", RawStorageMode.STORE_REDACTED, "empty", "", "[REDACTED]"),
        Row("sanitizeRawOcrNullable", RawStorageMode.STORE_METADATA_ONLY, "empty", "", null),
        Row("sanitizeNotificationText", RawStorageMode.STORE_RAW, "empty", "", ""),
        Row("sanitizeNotificationText", RawStorageMode.STORE_REDACTED, "empty", "", "[REDACTED]"),
        // Review snippet length cap is 200 chars, applied only under STORE_RAW.
        Row("sanitizedOcrReviewSnippet", RawStorageMode.STORE_RAW, "len-200", capLong200, capLong200),
        Row("sanitizedOcrReviewSnippet", RawStorageMode.STORE_RAW, "len-201", capLong201, capLong200),
        Row(
            "sanitizedOcrReviewSnippet", RawStorageMode.STORE_RAW, "len-500-sentinel-prefix",
            snippetSentinelPad,
            snippetSentinelPad.take(200),
        ),
        Row(
            "sanitizedOcrReviewSnippet", RawStorageMode.STORE_REDACTED, "len-500",
            "SECRET_CAP_REDACTED_XYZ" + "A".repeat(300), "[REDACTED]", sentinel = "SECRET_CAP_REDACTED_XYZ",
        ),
        Row(
            "sanitizedOcrReviewSnippet", RawStorageMode.STORE_METADATA_ONLY, "len-500",
            "SECRET_CAP_META_XYZ" + "A".repeat(300),
            "Receipt OCR captured; raw text storage disabled.", sentinel = "SECRET_CAP_META_XYZ",
        ),
        Row(
            "sanitizedOcrReviewSnippet", RawStorageMode.DO_NOT_STORE, "len-500",
            "SECRET_CAP_DNS_XYZ" + "A".repeat(300),
            "Receipt OCR captured; raw text not stored.", sentinel = "SECRET_CAP_DNS_XYZ",
        ),
        // Hash supplied without a message id: only METADATA_ONLY surfaces the hash.
        Row("sanitizeEmailMessageIdWithHash", RawStorageMode.STORE_METADATA_ONLY, "null-id-with-hash", null, "abc123HASH", hash = "abc123HASH"),
        Row("sanitizeEmailMessageIdWithHash", RawStorageMode.DO_NOT_STORE, "null-id-with-hash", null, null, hash = "abc123HASH"),
        Row("sanitizeEmailMessageIdWithHash", RawStorageMode.STORE_RAW, "null-id-with-hash", null, null, hash = "abc123HASH"),
        // Extras JSON: only STORE_RAW passes through; REDACTED is null (known inconsistency R2).
        Row("sanitizeNotificationExtras", RawStorageMode.STORE_RAW, "json", """{"k":"v"}""", """{"k":"v"}"""),
        Row("sanitizeNotificationExtras", RawStorageMode.STORE_REDACTED, "json", """{"SECRET_JSON_XYZ":"v"}""", null, sentinel = "SECRET_JSON_XYZ"),
    )

    private val allRows: List<Row> = baseRows + nullInputRows + extraRows

    // ── table guards ──────────────────────────────────────────────────────────

    @Test
    fun table_has_exactly_one_sentinel_row_per_function_and_mode() {
        for (fn in allFunctions) {
            val rows = baseRows.filter { it.fn == fn }
            assertEquals("base rows for $fn must equal RawStorageMode.entries.size",
                RawStorageMode.entries.size, rows.size)
            assertEquals("base rows for $fn must cover each mode once",
                RawStorageMode.entries.toSet(), rows.map { it.mode }.toSet())
        }
        assertEquals("no unknown functions in base table", allFunctions.toSet(), baseRows.map { it.fn }.toSet())
    }

    @Test
    fun table_has_exactly_one_null_row_per_nullable_function_and_mode() {
        for (fn in nullableInputFunctions) {
            val rows = nullInputRows.filter { it.fn == fn }
            assertEquals("null rows for $fn must equal RawStorageMode.entries.size",
                RawStorageMode.entries.size, rows.size)
            assertEquals("null rows for $fn must cover each mode once",
                RawStorageMode.entries.toSet(), rows.map { it.mode }.toSet())
        }
        assertEquals(nullableInputFunctions.toSet(), nullInputRows.map { it.fn }.toSet())
    }

    @Test
    fun table_row_ids_are_unique() {
        val duplicates = allRows.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue("duplicate row ids: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun sentinels_are_unique_per_function_and_mode() {
        val sentinels = baseRows.mapNotNull { it.sentinel }
        assertEquals("sentinels must be unique", sentinels.size, sentinels.toSet().size)
        baseRows.forEach { row ->
            assertEquals(
                "row ${row.id} sentinel must follow SECRET_<FN>_<MODE>_XYZ",
                "SECRET_${row.fn}_${row.mode.name}_XYZ",
                row.sentinel,
            )
        }
    }

    // ── behaviour pins ────────────────────────────────────────────────────────

    @Test
    fun live_sanitizers_sentinel_rows_match_pinned_output_and_never_leak_in_non_raw_modes() {
        runRows(baseRows)
    }

    @Test
    fun live_sanitizers_null_input_rows_match_pinned_output() {
        runRows(nullInputRows)
    }

    @Test
    fun live_sanitizers_boundary_rows_match_pinned_output_and_never_leak_in_non_raw_modes() {
        runRows(extraRows)
    }

    @Test
    fun snippet_cap_keeps_exactly_200_chars_under_store_raw() {
        val out = RawContentSanitizer.sanitizedOcrReviewSnippet("x".repeat(1000), RawStorageMode.STORE_RAW)
        assertEquals(200, out.length)
    }

    @Test
    fun email_message_id_hash_from_real_service_contains_no_plaintext_id() {
        val id = "SECRET_MSGID_HASHCHECK_XYZ@example.test"
        val hash = hashService.hmacSha256Prefix(id, EMAIL_MESSAGE_ID_PURPOSE)
        val out = RawContentSanitizer.sanitizeEmailMessageIdWithHash(id, hash, RawStorageMode.STORE_METADATA_ONLY)
        assertEquals(hash, out)
        // Default length is 24 lowercase hex chars; anything else means the format contract changed.
        assertTrue("hash must be 24 lowercase hex chars but was '$hash'", Regex("^[0-9a-f]{24}$").matches(out!!))
        assertFalse("hash must not contain plaintext id", out!!.contains("SECRET_MSGID_HASHCHECK_XYZ"))
    }

    @Test
    fun public_api_is_exactly_the_covered_functions_plus_bank_description() {
        val publicNames = RawContentSanitizer::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            "RawContentSanitizer public surface changed: add a table row (or a deliberate exclusion) for new functions",
            allFunctions.toSet() + "sanitizeBankDescription",
            publicNames,
        )
    }

    private fun runRows(rows: List<Row>) {
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                val actual = invoke(row)
                assertEquals("row ${row.id}: output", row.expected, actual)
                val s = row.sentinel
                if (s != null && row.mode != RawStorageMode.STORE_RAW) {
                    assertFalse(
                        "row ${row.id}: sentinel leaked into output '$actual'",
                        actual?.contains(s) == true,
                    )
                }
            } catch (e: AssertionError) {
                failures += "${row.id}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size}/${rows.size} rows failed:\n" + failures.joinToString("\n"))
        }
    }
}
