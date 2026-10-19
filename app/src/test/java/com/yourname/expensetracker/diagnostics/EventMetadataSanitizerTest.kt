package com.yourname.expensetracker.diagnostics

import com.yourname.expensetracker.domain.diagnostics.AppPipeline
import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import com.yourname.expensetracker.domain.diagnostics.EventOutcome
import com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consolidated table tests for [EventMetadataSanitizer] and [SafeEventMetadata]
 * (B6-12). Every row pins an exact output; no row uses "contains" or
 * conditional checks. Synthetic data only.
 *
 * Families: key classification, hash-key values, string values / exception
 * messages, scalar+enum values, container values, unknown-object toString,
 * sanitizeJsonString, SafeEventMetadata builder/merge.
 *
 * Release follow-up: accountIdHash and counterpartyHash are dangerous in this
 * sanitizer but approved in SafePrivacyMetadata. Those rows pin the current
 * behaviour only.
 */
class EventMetadataSanitizerTest {

    private val sanitizer = EventMetadataSanitizer()

    // ── Row types and harness ─────────────────────────────────────────────────

    private data class KeyRow(val id: String, val key: String, val dangerous: Boolean)
    private data class CanonRow(val id: String, val key: String, val canonical: String)
    private data class ApprovedRow(val id: String, val key: String, val approved: Boolean)
    private data class ValueRow(val id: String, val key: String, val input: Any?, val expected: Any?)
    private data class StringRow(val id: String, val input: String, val expected: String)
    private data class JsonRow(val id: String, val input: String?, val expected: Any?)
    private data class BuilderRow(
        val id: String,
        val build: () -> SafeEventMetadata,
        val expected: Map<String, Any?>
    )

    private class ToStringProbe(private val text: String) {
        override fun toString(): String = text
    }

    /** Runs every row, collecting failures (AssertionError only) so one run reports all bad rows. */
    private fun <R> checkRows(
        family: String,
        rows: List<R>,
        expectedSize: Int,
        id: (R) -> String,
        check: (R) -> Unit
    ) {
        assertEquals("$family: row count guard", expectedSize, rows.size)
        val ids = rows.map(id)
        assertEquals("$family: row ids must be unique", ids.size, ids.toSet().size)
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                check(row)
            } catch (e: AssertionError) {
                failures += "${id(row)}: ${e.message}"
            }
        }
        assertTrue("$family: ${failures.size} failing row(s):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Parses a JSON object string into order-insensitive plain Kotlin values (integral numbers as Long). */
    private fun plain(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> {
            val result = LinkedHashMap<String, Any?>()
            for (k in value.keys()) result[k] = plain(value.get(k))
            result
        }
        value is JSONArray -> (0 until value.length()).map { plain(value.get(it)) }
        value is Int -> value.toLong()
        else -> value
    }

    private fun plainJson(json: String): Any? = plain(JSONObject(json))

    private val redacted = "[REDACTED]"

    // ── Family 1: key classification ──────────────────────────────────────────

    @Test
    fun `key classification rows`() {
        val rows = listOf(
            // exact-safe keys
            KeyRow("K01", "expenseId", false),
            KeyRow("K02", "receiptId", false),
            KeyRow("K03", "operationType", false),
            KeyRow("K04", "stage", false),
            KeyRow("K05", "count", false),
            KeyRow("K06", "elapsed", false),
            KeyRow("K07", "correlationId", false),
            KeyRow("K08", "entityId", false),
            KeyRow("K09", "reason", false),
            KeyRow("K10", "sideEffect", false),
            KeyRow("K11", "delivered", false),
            KeyRow("K12", "currency", false),
            KeyRow("K13", "confidence", false),
            KeyRow("K14", "retryable", false),
            KeyRow("K15", "source", false),
            KeyRow("K16", "status", false),
            KeyRow("K17", "reason_code", false),
            KeyRow("K18", "elapsedMs", false),
            // exact-blocked and substring-blocked keys
            KeyRow("K20", "rawText", true),
            KeyRow("K21", "raw_text", true),
            KeyRow("K22", "rawOcr", true),
            KeyRow("K23", "accessToken", true),
            KeyRow("K24", "access_token", true),
            KeyRow("K25", "prompt", true),
            KeyRow("K26", "body", true),
            KeyRow("K27", "emailBody", true),
            KeyRow("K28", "fullPath", true),
            KeyRow("K29", "filePath", true),
            KeyRow("K30", "iban", true),
            KeyRow("K31", "accountNumber", true),
            KeyRow("K32", "password", true),
            KeyRow("K33", "secret", true),
            KeyRow("K34", "authorization", true),
            KeyRow("K35", "token", true),
            KeyRow("K36", "refreshToken", true),
            KeyRow("K37", "cvv", true),
            KeyRow("K38", "pin", true),
            KeyRow("K39", "PIN", true),
            KeyRow("K40", "sender", true),
            KeyRow("K41", "emailSubject", true),
            KeyRow("K42", "bankDescription", true),
            KeyRow("K43", "cardNumber", true),
            KeyRow("K44", "bearerValue", true),
            KeyRow("K45", "relativePath", true),
            KeyRow("K46", "token2", true),
            // traps: safe-exact prefix does not bypass the substring check (no prefix bypass)
            KeyRow("K50", "sourceRawText", true),
            KeyRow("K51", "sourceFullPath", true),
            KeyRow("K52", "sourceAccessToken", true),
            KeyRow("K53", "statusToken", true),
            KeyRow("K54", "reasonAuthorization", true),
            KeyRow("K55", "currencyAccountNumber", true),
            // traps: substring false positives are dangerous by design
            KeyRow("K60", "mapping", true),
            KeyRow("K61", "discard", true),
            KeyRow("K62", "authority", true),
            // Policy gap - release follow-up (SENSITIVE_DIAGNOSTICS_POLICY forbids merchant names/amounts);
            // pins CURRENT behaviour only
            KeyRow("K65", "description", false),
            KeyRow("K66", "merchant", false),
            KeyRow("K67", "amount", false),
            // empty / punctuation-only keys canonicalize to "" and are not dangerous
            KeyRow("K68", "", false),
            KeyRow("K69", "___", false),
            // approved hash keys are safe, even when they contain a blocked substring (path)
            KeyRow("K70", "sourceIdHash", false),
            KeyRow("K71", "notificationKeyHash", false),
            KeyRow("K72", "packageNameHash", false),
            KeyRow("K73", "packageHash", false),
            KeyRow("K74", "messageIdHash", false),
            KeyRow("K75", "providerHash", false),
            KeyRow("K76", "providerTransactionIdHash", false),
            KeyRow("K77", "externalHash", false),
            KeyRow("K78", "payloadHash", false),
            KeyRow("K79", "contentFingerprintHash", false),
            KeyRow("K80", "fileHash", false),
            KeyRow("K81", "backupHash", false),
            KeyRow("K82", "assetRelativePathHash", false),
            KeyRow("K83", "source_id_hash", false),
            KeyRow("K84", "SOURCEIDHASH", false),
            // unapproved keys ending in hash are dangerous (hash rule precedes substring rule)
            KeyRow("K90", "rawTextHash", true),
            KeyRow("K91", "accessTokenHash", true),
            KeyRow("K92", "userHash", true),
            KeyRow("K93", "hash", true),
            KeyRow("K94", "idHash", true),
            // Inconsistent with SafePrivacyMetadata (which approves these) - release follow-up.
            KeyRow("K95", "accountIdHash", true),
            KeyRow("K96", "counterpartyHash", true),
            // hash is only special as a suffix
            KeyRow("K97", "sourceIdHashX", false)
        )
        checkRows("key classification", rows, 82, { it.id }) { r ->
            assertEquals("isDangerousKey('${r.key}')", r.dangerous, sanitizer.isDangerousKey(r.key))
        }
    }

    @Test
    fun `canonicalizeKey rows`() {
        val rows = listOf(
            CanonRow("C01", "sourceIdHash", "sourceidhash"),
            CanonRow("C02", "Source_Id-Hash", "sourceidhash"),
            CanonRow("C03", "access.token", "accesstoken"),
            CanonRow("C04", "a b\tc", "abc"),
            CanonRow("C05", "KEY123", "key123"),
            CanonRow("C06", "", ""),
            CanonRow("C07", "___", "")
        )
        checkRows("canonicalizeKey", rows, 7, { it.id }) { r ->
            assertEquals("canonicalizeKey('${r.key}')", r.canonical, sanitizer.canonicalizeKey(r.key))
        }
    }

    @Test
    fun `isApprovedHashKey rows`() {
        val rows = listOf(
            ApprovedRow("A01", "sourceIdHash", true),
            ApprovedRow("A02", "source_id_hash", true),
            ApprovedRow("A03", "assetRelativePathHash", true),
            ApprovedRow("A04", "rawTextHash", false),
            ApprovedRow("A05", "source", false),
            ApprovedRow("A06", "hash", false),
            // Inconsistent with SafePrivacyMetadata - release follow-up.
            ApprovedRow("A07", "accountIdHash", false),
            ApprovedRow("A08", "counterpartyHash", false)
        )
        checkRows("isApprovedHashKey", rows, 8, { it.id }) { r ->
            assertEquals("isApprovedHashKey('${r.key}')", r.approved, sanitizer.isApprovedHashKey(r.key))
        }
    }

    // ── Family 2: hash-key values ─────────────────────────────────────────────

    @Test
    fun `hash key value rows`() {
        val hex32 = "a1b2c3d4e5f607182930a4b5c6d7e8f9"
        val rows = listOf(
            ValueRow("H01", "packageHash", "com.private.bank", redacted),
            ValueRow("H02", "packageHash", hex32, hex32),
            ValueRow("H03", "rawTextHash", hex32, redacted),
            ValueRow("H04", "packageHash", "plain-text-not-hex", redacted),
            ValueRow("H05", "packageNameHash", "plain-text-not-hex", redacted),
            ValueRow("H06", "notificationKeyHash", "plain-text-not-hex", redacted),
            ValueRow("H07", "sourceIdHash", "plain-text-not-hex", redacted),
            ValueRow("H08", "providerHash", "plain-text-not-hex", redacted),
            ValueRow("H09", "externalHash", "plain-text-not-hex", redacted),
            ValueRow("H10", "sourceIdHash", "plain text, not a hash", redacted),
            ValueRow("H11", "sourceIdHash", "abcdef0123456789", "abcdef0123456789"),
            ValueRow("H12", "providerTransactionIdHash", "TXN-12345-BANK-RAW", redacted),
            ValueRow("H13", "notificationKeyHash", "a1b2c3d4e5f67890", "a1b2c3d4e5f67890"),
            // length boundaries of ^[a-fA-F0-9]{8,128}$
            ValueRow("H14", "sourceIdHash", "abcdef0", redacted),
            ValueRow("H15", "sourceIdHash", "abcdef01", "abcdef01"),
            ValueRow("H16", "sourceIdHash", "a".repeat(128), "a".repeat(128)),
            ValueRow("H17", "sourceIdHash", "a".repeat(129), redacted),
            ValueRow("H18", "sourceIdHash", "ABCDEF0123456789", "ABCDEF0123456789"),
            ValueRow("H19", "sourceIdHash", "abcdefg1", redacted),
            ValueRow("H20", "sourceIdHash", "", redacted),
            ValueRow("H21", "sourceIdHash", " abcdef0123456789", redacted),
            // non-String values in a hash slot are redacted
            ValueRow("H22", "sourceIdHash", 1234567890123456L, redacted),
            ValueRow("H23", "sourceIdHash", null, redacted),
            ValueRow("H24", "sourceIdHash", true, redacted),
            ValueRow("H25", "sourceIdHash", listOf("abcdef0123456789"), redacted),
            // Inconsistent with SafePrivacyMetadata - release follow-up.
            ValueRow("H26", "accountIdHash", "abcdef0123456789", redacted),
            ValueRow("H27", "counterpartyHash", "abcdef0123456789", redacted),
            ValueRow("H28", "accessTokenHash", "abcdef0123456789", redacted),
            ValueRow("H29", "source_id_hash", "abcdef0123456789", "abcdef0123456789"),
            ValueRow("H30", "assetRelativePathHash", "abcdef0123456789", "abcdef0123456789"),
            ValueRow("H31", "payloadHash", "abcdef0123456789", "abcdef0123456789"),
            ValueRow("H32", "userHash", "abcdef0123456789", redacted),
            // hex-only validation: an all-digit 18-char hash is kept (no long-digit redaction in hash slots)
            // Policy gap - release follow-up (inconsistent with the 12+ digit redaction documented in
            // SENSITIVE_DIAGNOSTICS_POLICY.md:76); pins CURRENT behaviour only
            ValueRow("H33", "sourceIdHash", "123456789012345678", "123456789012345678")
        )
        checkRows("hash key values", rows, 33, { it.id }) { r ->
            assertEquals("sanitizeValue('${r.key}', ...)", r.expected, sanitizer.sanitizeValue(r.key, r.input))
        }
    }

    // ── Family 3: string values / exception messages ──────────────────────────

    @Test
    fun `string value and exception message rows`() {
        val rows = listOf(
            StringRow("S01", "", ""),
            StringRow("S02", "Hello world", "Hello world"),
            StringRow("S03", "Failed to read /data/user/0/com.example/files/db.sqlite", "Failed to read [PATH]"),
            StringRow("S04", "Cannot read /data/user/0/com.example/files/db.sqlite", "Cannot read [PATH]"),
            // path boundary: slash + 10 non-space chars
            StringRow("S05", "/abcdefghi", "/abcdefghi"),
            StringRow("S06", "/abcdefghij", "[PATH]"),
            StringRow("S07", "see /abcdefghij end", "see [PATH] end"),
            StringRow("S08", "C:\\Users\\bob\\file.txt", "[PATH]"),
            StringRow("S09", "Transfer to DE89370400440532013000 failed", "Transfer to [REDACTED] failed"),
            // IBAN-like boundary: 2 letters + 2 digits + at least 4 alphanumerics
            StringRow("S10", "AB12CDEF", "[REDACTED]"),
            StringRow("S11", "AB12CDE", "AB12CDE"),
            StringRow("S12", "Account 1234567890123456 not found", "Account [REDACTED] not found"),
            // long-digit boundary: 11 kept, 12 redacted
            StringRow("S13", "id 12345678901 ok", "id 12345678901 ok"),
            StringRow("S14", "id 123456789012 ok", "id [REDACTED] ok"),
            StringRow("S15", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", "[REDACTED]"),
            StringRow("S16", "Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature", "[REDACTED]"),
            StringRow("S17", "bearer abcdefgh", "[REDACTED]"),
            // bearer token boundary: 8 non-space chars needed
            StringRow("S18", "Bearer abcdefg", "Bearer abcdefg"),
            StringRow("S19", "Auth: Bearer abcdefgh end", "Auth: [REDACTED] end"),
            StringRow("S20", "abc123def456.ghi789jkl012.mno345pqr678", "[REDACTED]"),
            // JWT segment boundary: 10 chars each
            StringRow("S21", "abcdefghi.abcdefghi.abcdefghi", "abcdefghi.abcdefghi.abcdefghi"),
            StringRow("S22", "abcdefghij.abcdefghij.abcdefghij", "[REDACTED]"),
            // length boundaries: 256 kept, 257..512 truncated to 256, 513+ redacted
            StringRow("S23", "a".repeat(256), "a".repeat(256)),
            StringRow("S24", "a".repeat(257), "a".repeat(256)),
            StringRow("S25", "a".repeat(512), "a".repeat(256)),
            StringRow("S26", "a".repeat(513), redacted),
            StringRow("S27", "a".repeat(1000), redacted),
            StringRow("S28", "error: " + "x".repeat(1000), redacted),
            StringRow("S29", "error: " + "x".repeat(300), "error: " + "x".repeat(249)),
            // redaction happens before truncation; truncation can cut the placeholder
            StringRow("S30", "a".repeat(250) + "123456789012", "a".repeat(250) + "[REDAC"),
            StringRow("S31", "line1\nline2", "line1\nline2"),
            StringRow("S32", "h\u00e9llo", "h\u00e9llo"),
            // path replacement runs before the long-digit replacement
            StringRow("S33", "/tmp/1234567890123456", "[PATH]")
        )
        checkRows("string values", rows, 33, { it.id }) { r ->
            assertEquals("sanitizeValue('note', String)", r.expected, sanitizer.sanitizeValue("note", r.input))
            assertEquals("sanitizeExceptionMessage(String)", r.expected, sanitizer.sanitizeExceptionMessage(r.input))
        }
    }

    @Test
    fun `exception message null stays null`() {
        assertNull(sanitizer.sanitizeExceptionMessage(null))
    }

    // ── Scalars, enums, null ──────────────────────────────────────────────────

    @Test
    fun `scalar enum and null value rows`() {
        val rows = listOf(
            ValueRow("V01", "count", 5, 5),
            ValueRow("V02", "retryable", true, true),
            ValueRow("V03", "count", 42L, 42L),
            ValueRow("V04", "confidence", 1.5, 1.5),
            ValueRow("V05", "note", null, null),
            // Policy gap - release follow-up (inconsistent with the 12+ digit redaction documented in
            // SENSITIVE_DIAGNOSTICS_POLICY.md:76); pins CURRENT behaviour only
            // Numbers are not string-sanitized: a 15-digit Long passes through ...
            ValueRow("V06", "note", 123456789012345L, 123456789012345L),
            // ... while the same digits as a String are redacted
            ValueRow("V07", "note", "123456789012345", redacted),
            ValueRow("V08", "kind", EventOutcome.FAILED_FINAL, "FAILED_FINAL"),
            ValueRow("V09", "token", EventOutcome.BLOCKED, redacted),
            ValueRow("V10", "rawText", null, redacted),
            ValueRow("V11", "password", 5, redacted),
            ValueRow("V12", "note", 'x', "x"),
            ValueRow("V13", "retryable", false, false),
            ValueRow("V14", "kind", AppPipeline.EMAIL, "EMAIL")
        )
        checkRows("scalar/enum values", rows, 14, { it.id }) { r ->
            assertEquals("sanitizeValue('${r.key}', ...)", r.expected, sanitizer.sanitizeValue(r.key, r.input))
        }
    }

    // ── Container values (Map / Iterable / Array recursion) ───────────────────

    @Test
    fun `container value rows`() {
        val rows = listOf(
            ValueRow(
                "C01", "meta",
                mapOf("rawText" to "x", "count" to 5, "note" to "Bearer abcdefgh"),
                mapOf("rawText" to redacted, "count" to 5, "note" to redacted)
            ),
            ValueRow(
                "C02", "meta",
                mapOf("outer" to mapOf("password" to "p", "stage" to "s")),
                mapOf("outer" to mapOf("password" to redacted, "stage" to "s"))
            ),
            ValueRow("C03", "meta", mapOf(42 to "v"), mapOf("42" to "v")),
            ValueRow("C04", "token", mapOf("a" to "b"), redacted),
            ValueRow(
                "C05", "items",
                listOf("Bearer abcdefgh", "ok", 5, null, true),
                listOf(redacted, "ok", 5, null, true)
            ),
            ValueRow("C06", "items", arrayOf("ok", "/abcdefghij"), listOf("ok", "[PATH]")),
            ValueRow("C07", "items", listOf(listOf("/abcdefghij")), listOf(listOf("[PATH]"))),
            ValueRow("C08", "items", linkedSetOf("a", "/abcdefghij"), listOf("a", "[PATH]")),
            ValueRow("C09", "items", listOf(EventOutcome.BLOCKED), listOf("BLOCKED")),
            ValueRow(
                "C10", "items",
                listOf(mapOf("rawText" to "s", "count" to 1)),
                listOf(mapOf("rawText" to redacted, "count" to 1))
            ),
            ValueRow("C11", "authToken", listOf("a"), redacted),
            // Policy gap - release follow-up (inconsistent with the 12+ digit redaction documented in
            // SENSITIVE_DIAGNOSTICS_POLICY.md:76); pins CURRENT behaviour only
            ValueRow("C12", "items", listOf(123456789012345L), listOf(123456789012345L)),
            ValueRow("C13", "items", emptyList<Any>(), emptyList<Any>()),
            ValueRow("C14", "meta", emptyMap<String, Any?>(), emptyMap<String, Any?>()),
            ValueRow(
                "C15", "meta",
                mapOf("items" to listOf("/abcdefghij")),
                mapOf("items" to listOf("[PATH]"))
            ),
            ValueRow("C16", "items", 1..3, listOf(1, 2, 3))
        )
        checkRows("container values", rows, 16, { it.id }) { r ->
            assertEquals("sanitizeValue('${r.key}', container)", r.expected, sanitizer.sanitizeValue(r.key, r.input))
        }
    }

    @Test
    fun `sanitizeMap applies key rules per entry`() {
        val result = sanitizer.sanitizeMap(
            mapOf("rawText" to "x", "count" to 5, "sourceIdHash" to "not-hex", "note" to "/abcdefghij")
        )
        assertEquals(
            mapOf("rawText" to redacted, "count" to 5, "sourceIdHash" to redacted, "note" to "[PATH]"),
            result
        )
    }

    // ── Family 5: unknown-object toString ─────────────────────────────────────

    @Test
    fun `unknown object toString rows`() {
        val rows = listOf(
            ValueRow(
                "O01", "data",
                ToStringProbe("Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"),
                redacted
            ),
            ValueRow(
                "O02", "info",
                ToStringProbe("Error reading /data/user/0/com.example/secret.key"),
                "Error reading [PATH]"
            ),
            ValueRow("O03", "info", ToStringProbe("hello"), "hello"),
            ValueRow("O04", "info", ToStringProbe("a".repeat(513)), redacted),
            ValueRow("O05", "info", ToStringProbe("a".repeat(300)), "a".repeat(256)),
            ValueRow("O06", "token", ToStringProbe("hello"), redacted),
            ValueRow("O07", "info", ToStringProbe("acct 1234567890123456"), "acct [REDACTED]")
        )
        checkRows("unknown object toString", rows, 7, { it.id }) { r ->
            assertEquals("sanitizeValue('${r.key}', object)", r.expected, sanitizer.sanitizeValue(r.key, r.input))
        }
    }

    // ── Family 4: sanitizeJsonString ──────────────────────────────────────────

    @Test
    fun `sanitizeJsonString rows`() {
        val rows = listOf(
            // null result cases
            JsonRow("J01", null, null),
            JsonRow("J02", "", null),
            JsonRow("J03", "   ", null),
            JsonRow("J04", "{}", null),
            JsonRow("J05", "{ }", null),
            JsonRow("J06", "not json", null),
            JsonRow("J07", "[1,2]", null),
            JsonRow("J08", "{\"a\":", null),
            // nested objects and keys
            JsonRow(
                "J09", """{"outer":{"prompt":"do something bad"}}""",
                mapOf("outer" to mapOf("prompt" to redacted))
            ),
            JsonRow(
                "J10", """{"info":"Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"}""",
                mapOf("info" to redacted)
            ),
            JsonRow(
                "J11", """{"token":"abc123def456.ghi789jkl012.mno345pqr678"}""",
                mapOf("token" to redacted)
            ),
            JsonRow(
                "J12", """{"note":"abc123def456.ghi789jkl012.mno345pqr678"}""",
                mapOf("note" to redacted)
            ),
            JsonRow(
                "J13", """{"description":"${"a".repeat(1000)}"}""",
                mapOf("description" to redacted)
            ),
            JsonRow(
                "J14", """{"note":"${"a".repeat(300)}"}""",
                mapOf("note" to "a".repeat(256))
            ),
            // hash-key values are validated inside JSON too (DDL-512-07)
            JsonRow(
                "J15", """{"sourceIdHash":"plain-raw-external-id-not-hex"}""",
                mapOf("sourceIdHash" to redacted)
            ),
            JsonRow(
                "J16", """{"sourceIdHash":"deadbeef01234567abcdef0123456789"}""",
                mapOf("sourceIdHash" to "deadbeef01234567abcdef0123456789")
            ),
            JsonRow(
                "J17", """{"items":[{"rawText":"sensitive","count":5}]}""",
                mapOf("items" to listOf(mapOf("rawText" to redacted, "count" to 5L)))
            ),
            JsonRow(
                "J18", """{"nested":[[{"token":"secret123"}]]}""",
                mapOf("nested" to listOf(listOf(mapOf("token" to redacted))))
            ),
            JsonRow(
                "J19", """{"list":["Bearer abcdefgh","ok",5,true]}""",
                mapOf("list" to listOf(redacted, "ok", 5L, true))
            ),
            JsonRow(
                "J20", """{"a":{"sourceIdHash":"zz-not-hex"}}""",
                mapOf("a" to mapOf("sourceIdHash" to redacted))
            ),
            JsonRow("J21", """{"token":{"x":"y"}}""", mapOf("token" to redacted)),
            // Inconsistent with SafePrivacyMetadata - release follow-up.
            JsonRow(
                "J22", """{"accountIdHash":"abcdef0123456789"}""",
                mapOf("accountIdHash" to redacted)
            ),
            JsonRow(
                "J23", """{"a":{"b":{"c":{"password":"hunter2"}}}}""",
                mapOf("a" to mapOf("b" to mapOf("c" to mapOf("password" to redacted))))
            ),
            JsonRow(
                "J24", """{"memo":"pay DE89370400440532013000 now"}""",
                mapOf("memo" to "pay [REDACTED] now")
            ),
            // JSON null in an object is stringified by the sanitizer fallback branch.
            JsonRow(
                "J25", """{"note":null,"count":1}""",
                mapOf("note" to "null", "count" to 1L)
            ),
            JsonRow("J26", """{"x":{}}""", mapOf("x" to emptyMap<String, Any?>())),
            JsonRow("J27", """{"list":["/abcdefghij"]}""", mapOf("list" to listOf("[PATH]"))),
            JsonRow("J28", """{"sourceIdHash":12345678}""", mapOf("sourceIdHash" to redacted)),
            JsonRow(
                "J29", """{"expenseId":1,"stage":"s","retryable":false}""",
                mapOf("expenseId" to 1L, "stage" to "s", "retryable" to false)
            ),
            JsonRow("J30", """{"Raw_Text":"x"}""", mapOf("Raw_Text" to redacted))
        )
        checkRows("sanitizeJsonString", rows, 30, { it.id }) { r ->
            val actual = sanitizer.sanitizeJsonString(r.input)
            if (r.expected == null) {
                assertNull("sanitizeJsonString must return null, got: $actual", actual)
            } else {
                assertNotNull("sanitizeJsonString must not return null", actual)
                assertEquals("sanitized JSON structure", r.expected, plainJson(actual!!))
            }
        }
    }

    // ── Family 6: SafeEventMetadata builder ───────────────────────────────────

    @Test
    fun `SafeEventMetadata builder rows`() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223..., sha256("") = e3b0c44298fc1c149afbf4c8...
        val abcPrefix = "ba7816bf8f01cfea"
        val emptyPrefix = "e3b0c44298fc1c14"
        val rows = listOf(
            BuilderRow(
                "B01",
                { SafeEventMetadata.builder().put("rawText", "sensitive data").put("expenseId", 123L).build() },
                mapOf("rawText" to redacted, "expenseId" to 123L)
            ),
            BuilderRow(
                "B02",
                { SafeEventMetadata.builder().put("rawTextHash", "plainTextValue").put("count", 5).build() },
                mapOf("rawTextHash" to redacted, "count" to 5L)
            ),
            BuilderRow(
                "B03",
                { SafeEventMetadata.builder().put("password", 123).build() },
                mapOf("password" to redacted)
            ),
            BuilderRow(
                "B04",
                { SafeEventMetadata.builder().putRedacted("customerNote").build() },
                mapOf("customerNote" to redacted)
            ),
            BuilderRow(
                "B05",
                { SafeEventMetadata.builder().putHashed("providerTransactionId", "abc").build() },
                mapOf("providerTransactionId" to abcPrefix)
            ),
            BuilderRow(
                "B06",
                { SafeEventMetadata.builder().putHashed("sourceIdHash", "abc").build() },
                mapOf("sourceIdHash" to abcPrefix)
            ),
            BuilderRow(
                "B07",
                { SafeEventMetadata.builder().putHashed("rawTextHash", "abc").build() },
                mapOf("rawTextHash" to redacted)
            ),
            // Inconsistent with SafePrivacyMetadata - release follow-up.
            BuilderRow(
                "B08",
                { SafeEventMetadata.builder().putHashed("accountIdHash", "abc").build() },
                mapOf("accountIdHash" to redacted)
            ),
            BuilderRow(
                "B09",
                { SafeEventMetadata.builder().putHashed("counterpartyHash", "abc").build() },
                mapOf("counterpartyHash" to redacted)
            ),
            BuilderRow(
                "B10",
                { SafeEventMetadata.builder().putHashed("providerTransactionId", null).build() },
                emptyMap()
            ),
            BuilderRow(
                "B11",
                { SafeEventMetadata.builder().putHashed("providerTransactionId", "").build() },
                mapOf("providerTransactionId" to emptyPrefix)
            ),
            BuilderRow(
                "B12",
                { SafeEventMetadata.builder().put("kind", EventOutcome.BLOCKED).build() },
                mapOf("kind" to "BLOCKED")
            ),
            BuilderRow(
                "B13",
                { SafeEventMetadata.builder().put("meta", mapOf("password" to "x", "stage" to "s")).build() },
                mapOf("meta" to mapOf("password" to redacted, "stage" to "s"))
            ),
            BuilderRow(
                "B14",
                { SafeEventMetadata.builder().put("items", listOf("ok", "/abcdefghij")).build() },
                mapOf("items" to listOf("ok", "[PATH]"))
            ),
            BuilderRow(
                "B15",
                { SafeEventMetadata.builder().put("count", 1).put("count", 2).build() },
                mapOf("count" to 2L)
            ),
            BuilderRow(
                "B16",
                { SafeEventMetadata.builder().put("sourceIdHash", "abcdef0123456789").build() },
                mapOf("sourceIdHash" to "abcdef0123456789")
            ),
            BuilderRow(
                "B17",
                { SafeEventMetadata.builder().put("sourceIdHash", "plain text").build() },
                mapOf("sourceIdHash" to redacted)
            ),
            BuilderRow(
                "B18",
                {
                    SafeEventMetadata.builder()
                        .putHashed("providerTransactionId", "abc")
                        .put("currency", "EUR")
                        .build()
                },
                mapOf("providerTransactionId" to abcPrefix, "currency" to "EUR")
            ),
            // Inconsistent with SafePrivacyMetadata - release follow-up.
            BuilderRow(
                "B19",
                { SafeEventMetadata.builder().put("accountIdHash", "abcdef0123456789").build() },
                mapOf("accountIdHash" to redacted)
            ),
            BuilderRow("B20", { SafeEventMetadata.empty() }, emptyMap()),
            BuilderRow(
                "B21",
                { SafeEventMetadata.builder().put("note", "Bearer abcdefgh").build() },
                mapOf("note" to redacted)
            )
        )
        checkRows("SafeEventMetadata builder", rows, 21, { it.id }) { r ->
            assertEquals("toJson structure", r.expected, plainJson(r.build().toJson()))
        }
    }

    @Test
    fun `SafeEventMetadata isEmpty and empty json`() {
        assertTrue(SafeEventMetadata.empty().isEmpty())
        assertEquals("{}", SafeEventMetadata.empty().toJson())
        assertTrue(SafeEventMetadata.builder().build().isEmpty())
        assertTrue(SafeEventMetadata.builder().putHashed("providerTransactionId", null).build().isEmpty())
        assertFalse(SafeEventMetadata.builder().put("count", 1).build().isEmpty())
        assertFalse(SafeEventMetadata.builder().putRedacted("customerNote").build().isEmpty())
    }

    @Test
    fun `SafeEventMetadata putHashed never stores the raw value`() {
        val raw = "TXN-12345-BANK-RAW"
        val json = SafeEventMetadata.builder().putHashed("providerTransactionId", raw).build().toJson()
        val stored = plainJson(json) as Map<*, *>
        val value = stored["providerTransactionId"] as String
        assertTrue("stored value must be exactly 16 lowercase hex chars: $value", Regex("^[0-9a-f]{16}$").matches(value))
        assertFalse("stored value must differ from the raw value", value == raw)
        assertEquals(
            "hashing is deterministic",
            value,
            (plainJson(SafeEventMetadata.builder().putHashed("providerTransactionId", raw).build().toJson()) as Map<*, *>)["providerTransactionId"]
        )
    }

    @Test
    fun `SafeEventMetadata merge disjoint keys keeps both`() {
        val a = SafeEventMetadata.builder().put("expenseId", 1L).build()
        val b = SafeEventMetadata.builder().put("sideEffect", "budget_check").build()
        assertEquals(
            mapOf("expenseId" to 1L, "sideEffect" to "budget_check"),
            plainJson(a.merge(b).toJson())
        )
    }

    @Test
    fun `SafeEventMetadata merge overlapping key takes the other value`() {
        val a = SafeEventMetadata.builder().put("count", 1).put("stage", "a").build()
        val b = SafeEventMetadata.builder().put("count", 2).build()
        assertEquals(mapOf("count" to 2L, "stage" to "a"), plainJson(a.merge(b).toJson()))
    }

    @Test
    fun `SafeEventMetadata merge with empty returns the same instance`() {
        val a = SafeEventMetadata.builder().put("count", 5).build()
        assertSame(a, a.merge(SafeEventMetadata.empty()))
    }

    @Test
    fun `SafeEventMetadata merge into empty receiver copies the other content`() {
        val b = SafeEventMetadata.builder().put("count", 5).build()
        assertEquals(mapOf("count" to 5L), plainJson(SafeEventMetadata.empty().merge(b).toJson()))
    }

    @Test
    fun `SafeEventMetadata merge does not mutate either operand`() {
        val a = SafeEventMetadata.builder().put("count", 1).build()
        val b = SafeEventMetadata.builder().put("count", 2).put("stage", "b").build()
        a.merge(b)
        assertEquals(mapOf("count" to 1L), plainJson(a.toJson()))
        assertEquals(mapOf("count" to 2L, "stage" to "b"), plainJson(b.toJson()))
    }

    @Test
    fun `SafeEventMetadata build snapshot is isolated from later builder writes`() {
        val builder = SafeEventMetadata.builder().put("count", 1)
        val built = builder.build()
        builder.put("count", 2).put("stage", "later")
        assertEquals(mapOf("count" to 1L), plainJson(built.toJson()))
    }
}
