package com.yourname.expensetracker.domain.privacy

import com.yourname.expensetracker.data.privacy.DefaultSensitiveHashingService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * B6-8: the single source-of-truth matrix for the stored-payload contract of every
 * raw-bearing persistence builder.
 *
 * 4 sources (NOTIFICATION, RECEIPT_OCR, EMAIL_RECEIPT, BANK) x 4 [RawStorageMode]s =
 * 16 cell rows, plus explicit gap rows (fail-closed null currency, bank null-in,
 * bank buildWithHashing) and the [RawContentSanitizer.sanitizeBankDescription] table.
 *
 * Rules enforced per row:
 *  - every field of the payload gets an exact expected value (written out below, not
 *    derived from production logic);
 *  - every raw input carries a unique sentinel per row AND per field
 *    (`SECRET_<SOURCE>_<MODE>_<FIELD>_XYZ`); hashes use `HASH_...` values that must pass
 *    through unchanged in every mode (dedupe is never lost);
 *  - for every non-STORE_RAW row no sentinel (and no `SECRET_` marker at all) may appear
 *    anywhere in `payload.toString()`.
 *
 * All failing rows are collected and reported together; only [AssertionError] is caught.
 */
class RawStoragePayloadMatrixTest {

    private enum class Source { NOTIFICATION, RECEIPT_OCR, EMAIL_RECEIPT, BANK }

    private class Built(val fields: Map<String, String?>, val rendered: String)

    private class Row(
        val id: String,
        val source: Source,
        val mode: RawStorageMode,
        /** True for the 16 source x mode cells; false for extra gap rows. */
        val cell: Boolean,
        val sentinels: List<String>,
        val expected: Map<String, String?>,
        val build: () -> Built
    )

    // ── Sentinels / canonical helpers ─────────────────────────────────────────

    private fun sec(src: String, mode: RawStorageMode, field: String) =
        "SECRET_${src}_${mode.name}_${field}_XYZ"

    private fun hash(src: String, mode: RawStorageMode, field: String) =
        "HASH_${src}_${mode.name}_${field}_XYZ"

    /** Order-independent rendering of the redacted-items JSON so key order cannot flake. */
    private fun canon(value: Any?): String = when (value) {
        is JSONObject -> {
            val keyList: List<String> = value.keys().asSequence().toList().sorted()
            keyList.joinToString(",", "{", "}") { k: String -> "$k=${canon(value.get(k))}" }
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { i -> canon(value.get(i)) }
        is Number -> value.toDouble().toString()
        else -> value.toString()
    }

    private fun canonicalItems(json: String?): String? = json?.let {
        try {
            canon(JSONObject(it))
        } catch (_: JSONException) {
            "NOT_A_JSON_OBJECT"
        }
    }

    /** Canonical form of the numeric projection of the single item produced by [parserItems]. */
    private val expectedProjection =
        "{items=[{currency=EUR,quantity=2.0,totalPrice=3.0,unitPrice=1.5}],schema=REDACTED_V1}"

    private fun parserItems(descriptionToken: String) =
        """[{"description":"$descriptionToken","quantity":2,"unitPrice":1.5,"totalPrice":3.0}]"""

    // ── Row builders ──────────────────────────────────────────────────────────

    private fun notificationRow(mode: RawStorageMode): Row {
        val src = "NOTIFICATION"
        val title = sec(src, mode, "TITLE")
        val text = sec(src, mode, "TEXT")
        val big = sec(src, mode, "BIGTEXT")
        val sub = sec(src, mode, "SUBTEXT")
        val extrasToken = sec(src, mode, "EXTRAS")
        val extras = """{"secret":"$extrasToken"}"""
        val dedupe = hash(src, mode, "DEDUPE_FINGERPRINT")
        val keyHash = hash(src, mode, "NOTIFICATION_KEY_HASH")
        val expected: Map<String, String?> = when (mode) {
            RawStorageMode.STORE_RAW -> mapOf(
                "rawNotificationTitle" to title,
                "rawNotificationText" to text,
                "rawNotificationBigText" to big,
                "rawNotificationSubText" to sub,
                "rawNotificationExtrasJson" to extras,
                "pendingReviewTitle" to title,
                "pendingReviewText" to text,
                "dedupeFingerprint" to dedupe,
                "notificationKeyHash" to keyHash,
                "mode" to mode.name
            )
            RawStorageMode.STORE_REDACTED -> mapOf(
                "rawNotificationTitle" to "[REDACTED]",
                "rawNotificationText" to "[REDACTED]",
                "rawNotificationBigText" to "[REDACTED]",
                "rawNotificationSubText" to "[REDACTED]",
                "rawNotificationExtrasJson" to """{"redacted":true}""",
                "pendingReviewTitle" to "[REDACTED]",
                "pendingReviewText" to "[REDACTED]",
                "dedupeFingerprint" to dedupe,
                "notificationKeyHash" to keyHash,
                "mode" to mode.name
            )
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE -> mapOf(
                "rawNotificationTitle" to null,
                "rawNotificationText" to null,
                "rawNotificationBigText" to null,
                "rawNotificationSubText" to null,
                "rawNotificationExtrasJson" to null,
                "pendingReviewTitle" to null,
                "pendingReviewText" to null,
                "dedupeFingerprint" to dedupe,
                "notificationKeyHash" to keyHash,
                "mode" to mode.name
            )
        }
        return Row(
            id = "NOTIFICATION/${mode.name}",
            source = Source.NOTIFICATION,
            mode = mode,
            cell = true,
            sentinels = listOf(title, text, big, sub, extrasToken),
            expected = expected,
            build = {
                val p = NotificationPersistencePayload.build(
                    mode = mode,
                    rawTitle = title,
                    rawText = text,
                    rawBigText = big,
                    rawSubText = sub,
                    extrasJson = extras,
                    dedupeFingerprint = dedupe,
                    notificationKeyHash = keyHash
                )
                Built(
                    mapOf(
                        "rawNotificationTitle" to p.rawNotificationTitle,
                        "rawNotificationText" to p.rawNotificationText,
                        "rawNotificationBigText" to p.rawNotificationBigText,
                        "rawNotificationSubText" to p.rawNotificationSubText,
                        "rawNotificationExtrasJson" to p.rawNotificationExtrasJson,
                        "pendingReviewTitle" to p.pendingReviewTitle,
                        "pendingReviewText" to p.pendingReviewText,
                        "dedupeFingerprint" to p.dedupeFingerprint,
                        "notificationKeyHash" to p.notificationKeyHash,
                        "mode" to p.mode.name
                    ),
                    p.toString()
                )
            }
        )
    }

    /**
     * @param currency null exercises the fail-closed path (REDACTED must omit items).
     */
    private fun receiptRow(mode: RawStorageMode, currency: String?, variant: String? = null): Row {
        val src = if (variant == null) "RECEIPT_OCR" else "RECEIPT_OCR_$variant"
        val ocrToken = sec(src, mode, "OCR_TEXT")
        require(ocrToken.length < 200) { "sentinel must fit inside the 200-char snippet window" }
        val ocrText = ocrToken + "A".repeat(300)
        val itemToken = sec(src, mode, "ITEM_DESCRIPTION")
        val items = parserItems(itemToken)
        val expected: Map<String, String?> = when (mode) {
            RawStorageMode.STORE_RAW -> mapOf(
                "rawOcrText" to ocrText,
                "reviewSnippet" to ocrToken + "A".repeat(200 - ocrToken.length),
                "parsedItemsJson" to items,
                "mode" to mode.name
            )
            RawStorageMode.STORE_REDACTED -> mapOf(
                "rawOcrText" to null,
                "reviewSnippet" to "[REDACTED]",
                "parsedItemsJson" to (if (currency != null) expectedProjection else null),
                "mode" to mode.name
            )
            RawStorageMode.STORE_METADATA_ONLY -> mapOf(
                "rawOcrText" to null,
                "reviewSnippet" to "Receipt OCR captured; raw text storage disabled.",
                "parsedItemsJson" to null,
                "mode" to mode.name
            )
            RawStorageMode.DO_NOT_STORE -> mapOf(
                "rawOcrText" to null,
                "reviewSnippet" to "Receipt OCR captured; raw text not stored.",
                "parsedItemsJson" to null,
                "mode" to mode.name
            )
        }
        val suffix = if (variant == null) "" else "/$variant"
        return Row(
            id = "RECEIPT_OCR/${mode.name}$suffix",
            source = Source.RECEIPT_OCR,
            mode = mode,
            cell = variant == null,
            sentinels = listOf(ocrToken, itemToken),
            expected = expected,
            build = {
                val p = ReceiptPersistencePayload.build(
                    mode = mode,
                    rawOcrText = ocrText,
                    parsedItemsJson = items,
                    currency = currency
                )
                val itemsField = if (mode == RawStorageMode.STORE_REDACTED) canonicalItems(p.parsedItemsJson) else p.parsedItemsJson
                Built(
                    mapOf(
                        "rawOcrText" to p.rawOcrText,
                        "reviewSnippet" to p.reviewSnippet,
                        "parsedItemsJson" to itemsField,
                        "mode" to p.mode.name
                    ),
                    p.toString()
                )
            }
        )
    }

    private fun emailRow(mode: RawStorageMode, currency: String?, variant: String? = null): Row {
        val src = if (variant == null) "EMAIL_RECEIPT" else "EMAIL_RECEIPT_$variant"
        val subject = sec(src, mode, "SUBJECT")
        val sender = sec(src, mode, "SENDER")
        val body = sec(src, mode, "BODY")
        val messageId = sec(src, mode, "MESSAGE_ID")
        val itemToken = sec(src, mode, "ITEM_DESCRIPTION")
        val items = parserItems(itemToken)
        val msgHash = hash(src, mode, "MESSAGE_ID_HASH")
        val fpHash = hash(src, mode, "CONTENT_FINGERPRINT_HASH")
        val orderHash = hash(src, mode, "PROVIDER_ORDER_ID_HASH")
        val expected: Map<String, String?> = when (mode) {
            RawStorageMode.STORE_RAW -> mapOf(
                "subject" to subject,
                "sender" to sender,
                "bodyText" to body,
                "messageIdStored" to messageId,
                "messageIdHash" to msgHash,
                "contentFingerprintHash" to fpHash,
                "providerOrderIdHash" to orderHash,
                "parsedItemsJson" to items,
                "mode" to mode.name
            )
            RawStorageMode.STORE_REDACTED -> mapOf(
                "subject" to "[REDACTED]",
                "sender" to "[REDACTED]",
                "bodyText" to null,
                "messageIdStored" to null,
                "messageIdHash" to msgHash,
                "contentFingerprintHash" to fpHash,
                "providerOrderIdHash" to orderHash,
                "parsedItemsJson" to (if (currency != null) expectedProjection else null),
                "mode" to mode.name
            )
            RawStorageMode.STORE_METADATA_ONLY -> mapOf(
                "subject" to null,
                "sender" to null,
                "bodyText" to null,
                "messageIdStored" to null,
                "messageIdHash" to msgHash,
                "contentFingerprintHash" to fpHash,
                "providerOrderIdHash" to orderHash,
                "parsedItemsJson" to null,
                "mode" to mode.name
            )
            RawStorageMode.DO_NOT_STORE -> mapOf(
                "subject" to null,
                "sender" to null,
                "bodyText" to null,
                "messageIdStored" to null,
                "messageIdHash" to msgHash,
                "contentFingerprintHash" to fpHash,
                "providerOrderIdHash" to null,
                "parsedItemsJson" to null,
                "mode" to mode.name
            )
        }
        val suffix = if (variant == null) "" else "/$variant"
        return Row(
            id = "EMAIL_RECEIPT/${mode.name}$suffix",
            source = Source.EMAIL_RECEIPT,
            mode = mode,
            cell = variant == null,
            sentinels = listOf(subject, sender, body, messageId, itemToken),
            expected = expected,
            build = {
                val p = EmailReceiptPersistencePayload.build(
                    mode = mode,
                    subject = subject,
                    sender = sender,
                    bodyText = body,
                    messageId = messageId,
                    messageIdHash = msgHash,
                    contentFingerprintHash = fpHash,
                    providerOrderIdHash = orderHash,
                    parsedItemsJson = items,
                    currency = currency
                )
                val itemsField = if (mode == RawStorageMode.STORE_REDACTED) canonicalItems(p.parsedItemsJson) else p.parsedItemsJson
                Built(
                    mapOf(
                        "subject" to p.subject,
                        "sender" to p.sender,
                        "bodyText" to p.bodyText,
                        "messageIdStored" to p.messageIdStored,
                        "messageIdHash" to p.messageIdHash,
                        "contentFingerprintHash" to p.contentFingerprintHash,
                        "providerOrderIdHash" to p.providerOrderIdHash,
                        "parsedItemsJson" to itemsField,
                        "mode" to p.mode.name
                    ),
                    p.toString()
                )
            }
        )
    }

    private fun bankRow(mode: RawStorageMode): Row {
        val src = "BANK"
        val desc = sec(src, mode, "DESCRIPTION")
        val ref = sec(src, mode, "REFERENCE")
        val notes = sec(src, mode, "NOTES")
        val cpHash = hash(src, mode, "COUNTERPARTY_HASH")
        val txHash = hash(src, mode, "PROVIDER_TRANSACTION_ID_HASH")
        val acctHash = hash(src, mode, "ACCOUNT_ID_HASH")
        val expected: Map<String, String?> = when (mode) {
            RawStorageMode.STORE_RAW -> bankExpected(mode, desc, ref, notes, cpHash, txHash, acctHash)
            RawStorageMode.STORE_REDACTED ->
                bankExpected(mode, "[REDACTED]", "[REDACTED]", "[REDACTED]", cpHash, txHash, acctHash)
            RawStorageMode.STORE_METADATA_ONLY, RawStorageMode.DO_NOT_STORE ->
                bankExpected(mode, null, null, null, cpHash, txHash, acctHash)
        }
        return bankRowOf("BANK/${mode.name}", mode, true, desc, ref, notes, cpHash, txHash, acctHash, expected)
    }

    private fun bankExpected(
        mode: RawStorageMode,
        description: String?,
        reference: String?,
        notes: String?,
        cpHash: String?,
        txHash: String?,
        acctHash: String?
    ): Map<String, String?> = mapOf(
        "redactedDescription" to description,
        "redactedReference" to reference,
        "counterpartyHash" to cpHash,
        "providerTransactionIdHash" to txHash,
        "accountIdHash" to acctHash,
        "notes" to notes,
        "mode" to mode.name
    )

    private fun bankRowOf(
        id: String,
        mode: RawStorageMode,
        cell: Boolean,
        desc: String?,
        ref: String?,
        notes: String?,
        cpHash: String?,
        txHash: String?,
        acctHash: String?,
        expected: Map<String, String?>
    ): Row = Row(
        id = id,
        source = Source.BANK,
        mode = mode,
        cell = cell,
        sentinels = listOfNotNull(desc, ref, notes),
        expected = expected,
        build = {
            val p = BankTransactionPersistencePayload.build(
                mode = mode,
                rawDescription = desc,
                rawReference = ref,
                counterpartyHash = cpHash,
                providerTransactionIdHash = txHash,
                accountIdHash = acctHash,
                notes = notes
            )
            Built(
                mapOf(
                    "redactedDescription" to p.redactedDescription,
                    "redactedReference" to p.redactedReference,
                    "counterpartyHash" to p.counterpartyHash,
                    "providerTransactionIdHash" to p.providerTransactionIdHash,
                    "accountIdHash" to p.accountIdHash,
                    "notes" to p.notes,
                    "mode" to p.mode.name
                ),
                p.toString()
            )
        }
    )

    /** BANK STORE_REDACTED where the raw inputs are absent: null must stay null. */
    private fun bankRedactedNullInRow(): Row {
        val mode = RawStorageMode.STORE_REDACTED
        val src = "BANK_NULLIN"
        val cpHash = hash(src, mode, "COUNTERPARTY_HASH")
        val txHash = hash(src, mode, "PROVIDER_TRANSACTION_ID_HASH")
        val acctHash = hash(src, mode, "ACCOUNT_ID_HASH")
        return bankRowOf(
            "BANK/STORE_REDACTED/null-in", mode, false,
            null, null, null, cpHash, txHash, acctHash,
            bankExpected(mode, null, null, null, cpHash, txHash, acctHash)
        )
    }

    /** BANK STORE_REDACTED with a mix of present and absent inputs. */
    private fun bankRedactedPartialNullInRow(): Row {
        val mode = RawStorageMode.STORE_REDACTED
        val src = "BANK_PARTIAL"
        val desc = sec(src, mode, "DESCRIPTION")
        val notes = sec(src, mode, "NOTES")
        val cpHash = hash(src, mode, "COUNTERPARTY_HASH")
        val txHash = hash(src, mode, "PROVIDER_TRANSACTION_ID_HASH")
        val acctHash = hash(src, mode, "ACCOUNT_ID_HASH")
        return bankRowOf(
            "BANK/STORE_REDACTED/partial-null-in", mode, false,
            desc, null, notes, cpHash, txHash, acctHash,
            bankExpected(mode, "[REDACTED]", null, "[REDACTED]", cpHash, txHash, acctHash)
        )
    }

    /**
     * BANK STORE_METADATA_ONLY through buildWithHashing with the real hashing service:
     * hashes are the service output for the documented purposes, never the raw value.
     */
    private fun bankWithHashingMetadataOnlyRow(): Row {
        val mode = RawStorageMode.STORE_METADATA_ONLY
        val src = "BANK_HASHING"
        val desc = sec(src, mode, "DESCRIPTION")
        val ref = sec(src, mode, "REFERENCE")
        val notes = sec(src, mode, "NOTES")
        val counterparty = sec(src, mode, "COUNTERPARTY")
        val txId = sec(src, mode, "PROVIDER_TRANSACTION_ID")
        val acct = sec(src, mode, "ACCOUNT_ID")
        val hashService = DefaultSensitiveHashingService()
        val expected = bankExpected(
            mode, null, null, null,
            hashService.hmacSha256Prefix(counterparty, "bankCounterparty"),
            hashService.hmacSha256Prefix(txId, "providerTransactionId"),
            hashService.hmacSha256Prefix(acct, "bankAccountId")
        )
        return Row(
            id = "BANK/STORE_METADATA_ONLY/with-hashing",
            source = Source.BANK,
            mode = mode,
            cell = false,
            sentinels = listOf(desc, ref, notes, counterparty, txId, acct),
            expected = expected,
            build = {
                val p = BankTransactionPersistencePayload.buildWithHashing(
                    mode = mode,
                    rawDescription = desc,
                    rawReference = ref,
                    counterparty = counterparty,
                    providerTransactionId = txId,
                    accountId = acct,
                    notes = notes,
                    hashService = hashService
                )
                Built(
                    mapOf(
                        "redactedDescription" to p.redactedDescription,
                        "redactedReference" to p.redactedReference,
                        "counterpartyHash" to p.counterpartyHash,
                        "providerTransactionIdHash" to p.providerTransactionIdHash,
                        "accountIdHash" to p.accountIdHash,
                        "notes" to p.notes,
                        "mode" to p.mode.name
                    ),
                    p.toString()
                )
            }
        )
    }

    // ── The matrix ────────────────────────────────────────────────────────────

    private val cellRows: List<Row> = listOf(
        notificationRow(RawStorageMode.STORE_RAW),
        notificationRow(RawStorageMode.STORE_REDACTED),
        notificationRow(RawStorageMode.STORE_METADATA_ONLY),
        notificationRow(RawStorageMode.DO_NOT_STORE),
        receiptRow(RawStorageMode.STORE_RAW, currency = "EUR"),
        receiptRow(RawStorageMode.STORE_REDACTED, currency = "EUR"),
        receiptRow(RawStorageMode.STORE_METADATA_ONLY, currency = "EUR"),
        receiptRow(RawStorageMode.DO_NOT_STORE, currency = "EUR"),
        emailRow(RawStorageMode.STORE_RAW, currency = "EUR"),
        emailRow(RawStorageMode.STORE_REDACTED, currency = "EUR"),
        emailRow(RawStorageMode.STORE_METADATA_ONLY, currency = "EUR"),
        emailRow(RawStorageMode.DO_NOT_STORE, currency = "EUR"),
        bankRow(RawStorageMode.STORE_RAW),
        bankRow(RawStorageMode.STORE_REDACTED),
        bankRow(RawStorageMode.STORE_METADATA_ONLY),
        bankRow(RawStorageMode.DO_NOT_STORE)
    )

    private val gapRows: List<Row> = listOf(
        receiptRow(RawStorageMode.STORE_REDACTED, currency = null, variant = "no-currency"),
        emailRow(RawStorageMode.STORE_REDACTED, currency = null, variant = "no-currency"),
        bankRedactedNullInRow(),
        bankRedactedPartialNullInRow(),
        bankWithHashingMetadataOnlyRow()
    )

    private val allRows: List<Row> = cellRows + gapRows

    private fun verify(row: Row) {
        val built = row.build()
        assertEquals("[${row.id}] payload field set", row.expected.keys, built.fields.keys)
        for ((field, want) in row.expected) {
            assertEquals("[${row.id}] field $field", want, built.fields[field])
        }
        if (row.mode != RawStorageMode.STORE_RAW) {
            for (sentinel in row.sentinels) {
                assertFalse(
                    "[${row.id}] sentinel $sentinel leaked into payload.toString()",
                    built.rendered.contains(sentinel)
                )
            }
            assertFalse(
                "[${row.id}] a SECRET_ marker leaked into payload.toString()",
                built.rendered.contains("SECRET_")
            )
        } else {
            // B6 nit (37): positive control. Every sentinel IS rendered by toString() in STORE_RAW,
            // so the non-STORE_RAW absence checks above can actually fail (they are not blind).
            for (sentinel in row.sentinels) {
                assertTrue(
                    "[${row.id}] control: sentinel $sentinel must be rendered in payload.toString() for STORE_RAW",
                    built.rendered.contains(sentinel)
                )
            }
        }
    }

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    fun matrix_guard_every_source_has_one_cell_row_per_storage_mode() {
        for (source in Source.entries) {
            val rows = cellRows.filter { it.source == source }
            assertEquals(
                "[$source] cell rows must equal RawStorageMode.entries.size",
                RawStorageMode.entries.size,
                rows.size
            )
            assertEquals(
                "[$source] cell rows must cover every RawStorageMode exactly once",
                RawStorageMode.entries.toSet(),
                rows.map { it.mode }.toSet()
            )
        }
    }

    @Test
    fun matrix_guard_total_cell_rows_and_unique_row_ids() {
        assertEquals(
            "4 sources x all RawStorageMode entries",
            Source.entries.size * RawStorageMode.entries.size,
            cellRows.size
        )
        assertEquals("16 cell rows", 16, cellRows.size)
        assertEquals("5 gap rows", 5, gapRows.size)
        assertTrue("no gap row may be flagged as a cell", gapRows.none { it.cell })
        assertTrue("every cell row must be flagged as a cell", cellRows.all { it.cell })
        assertEquals(
            "row ids must be unique",
            allRows.size,
            allRows.map { it.id }.toSet().size
        )
    }

    /**
     * B6 nit (34): the matrix' per-row field set is checked against what each payload class
     * actually declares (java reflection over instance fields), so adding a field to a payload
     * without extending the matrix fails here instead of silently going unchecked.
     */
    @Test
    fun matrix_guard_field_set_equals_declared_payload_fields_by_reflection() {
        val payloadClass: Map<Source, Class<*>> = mapOf(
            Source.NOTIFICATION to NotificationPersistencePayload::class.java,
            Source.RECEIPT_OCR to ReceiptPersistencePayload::class.java,
            Source.EMAIL_RECEIPT to EmailReceiptPersistencePayload::class.java,
            Source.BANK to BankTransactionPersistencePayload::class.java
        )
        assertEquals("every Source must map to a payload class", Source.entries.toSet(), payloadClass.keys)
        val failures = mutableListOf<String>()
        for ((source, clazz) in payloadClass) {
            val declared: Set<String> = clazz.declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .map { it.name }
                .toSet()
            for (row in allRows.filter { it.source == source }) {
                try {
                    assertEquals("[${row.id}] expected-map keys vs declared fields of ${clazz.simpleName}", declared, row.expected.keys)
                    assertEquals("[${row.id}] built-map keys vs declared fields of ${clazz.simpleName}", declared, row.build().fields.keys)
                } catch (e: AssertionError) {
                    failures += "${row.id}: ${e.message}"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} field-set rows failed:\n" + failures.joinToString("\n"))
        }
    }

    // ── Payload matrix ────────────────────────────────────────────────────────

    @Test
    fun payload_matrix_every_row_has_exact_fields_and_no_sentinel_outside_store_raw() {
        val failures = mutableListOf<String>()
        for (row in allRows) {
            try {
                verify(row)
            } catch (e: AssertionError) {
                failures += "${row.id}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${allRows.size} matrix rows failed:\n" + failures.joinToString("\n"))
        }
    }

    // ── RawContentSanitizer.sanitizeBankDescription ───────────────────────────

    @Test
    fun sanitizeBankDescription_table_exact_value_per_mode_without_leak() {
        // (mode, expected output) written out per row.
        val table: List<Pair<RawStorageMode, String?>> = listOf(
            RawStorageMode.STORE_RAW to "RAW_ECHO",
            RawStorageMode.STORE_REDACTED to "[REDACTED]",
            RawStorageMode.STORE_METADATA_ONLY to null,
            RawStorageMode.DO_NOT_STORE to null
        )
        assertEquals(
            "sanitizer table rows must equal RawStorageMode.entries.size",
            RawStorageMode.entries.size,
            table.size
        )
        assertEquals(
            "sanitizer table must cover every RawStorageMode exactly once",
            RawStorageMode.entries.toSet(),
            table.map { it.first }.toSet()
        )
        val failures = mutableListOf<String>()
        for ((mode, expectedOrEcho) in table) {
            val id = "SANITIZER/${mode.name}"
            val input = sec("SANITIZER", mode, "BANK_DESCRIPTION")
            val expected = if (expectedOrEcho == "RAW_ECHO") input else expectedOrEcho
            try {
                val actual = RawContentSanitizer.sanitizeBankDescription(input, mode)
                assertEquals("[$id] output", expected, actual)
                if (mode != RawStorageMode.STORE_RAW) {
                    assertFalse("[$id] input leaked into output", actual?.contains(input) ?: false)
                }
            } catch (e: AssertionError) {
                failures += "$id: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} sanitizer rows failed:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun sanitizeBankDescription_null_input_returns_null_for_all_modes() {
        val failures = mutableListOf<String>()
        for (mode in RawStorageMode.entries) {
            val id = "SANITIZER/${mode.name}/null-in"
            try {
                assertNull("[$id] null input must stay null", RawContentSanitizer.sanitizeBankDescription(null, mode))
            } catch (e: AssertionError) {
                failures += "$id: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} null-input rows failed:\n" + failures.joinToString("\n"))
        }
    }

    // ── Sanity: the redacted projection helper itself is strict ───────────────

    @Test
    fun matrix_canonical_projection_helper_exposes_extra_keys() {
        // Control for the helper used by the REDACTED rows: an extra "description" key must
        // change the canonical form, so a leaking projection cannot match expectedProjection.
        val leaking = """{"schema":"REDACTED_V1","items":[{"quantity":2,"unitPrice":1.5,"totalPrice":3.0,"currency":"EUR","description":"x"}]}"""
        val clean = """{"items":[{"totalPrice":3,"currency":"EUR","unitPrice":1.5,"quantity":2}],"schema":"REDACTED_V1"}"""
        assertEquals(expectedProjection, canonicalItems(clean))
        assertTrue(canonicalItems(leaking) != expectedProjection)
        assertNotNull(canonicalItems(leaking))
    }
}
