package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * refund-netting S2a regression: a backup written by a pre-153 build (schema 152, no refundNettedAmount
 * column) carries the LEGACY spending definition hash. Restore verifies the staged database BEFORE its
 * Room migration, so the legacy definition must be the one that is queried: the current spending SQL
 * references refundNettedAmount and would fail with SEMANTIC_QUERY_FAILED on that schema.
 *
 * The legacy hash is rebuilt from the frozen pre-153 definition text written out as literals (on-disk
 * manifest contract: first 16 hex characters of SHA-256 over `v1|ID|table|keys=..|filter=..|value=..`).
 * Expected bucket values are hand computed from the rows below, never read from production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesLegacyRestoreTest {

    private val innerEffectiveAmount =
        "CASE WHEN isNotMine = 1 THEN 0.0 " +
            "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount " +
            "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 " +
            "ELSE amount END"

    private val pre153SpendingValue =
        "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 ELSE ($innerEffectiveAmount) END"

    private val legacyHash: String = MessageDigest.getInstance("SHA-256")
        .digest(
            ("v1|SPENDING_EFFECTIVE_BY_CURRENCY|expenses|keys=currency|filter=transactionType = 'PURCHASE'|" +
                "value=$pre153SpendingValue").toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it) }
        .take(16)

    private val spending = SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY

    // ── Fixtures ─────────────────────────────────────────────────

    private val columns152 =
        "id INTEGER PRIMARY KEY, transactionType TEXT NOT NULL, currency TEXT NOT NULL, amount REAL, " +
            "isNotMine INTEGER NOT NULL DEFAULT 0, isSharedExpense INTEGER NOT NULL DEFAULT 0, " +
            "myShareAmount REAL, mySharePercentage REAL, spendingExclusionReason TEXT DEFAULT NULL"

    private fun createTables(db: SQLiteDatabase, withRefundNettedColumn: Boolean) {
        val extra = if (withRefundNettedColumn) ", refundNettedAmount REAL NOT NULL DEFAULT 0.0" else ""
        db.execSQL("CREATE TABLE expenses ($columns152$extra)")
        db.execSQL(
            "CREATE TABLE manual_recurring_expenses (id INTEGER PRIMARY KEY, isActive INTEGER NOT NULL, " +
                "currency TEXT NOT NULL, amount REAL NOT NULL)"
        )
        db.execSQL("INSERT INTO manual_recurring_expenses (id, isActive, currency, amount) VALUES (1, 1, 'EUR', 9.0)")
        db.execSQL("INSERT INTO manual_recurring_expenses (id, isActive, currency, amount) VALUES (2, 0, 'USD', 15.0)")
    }

    /** Schema-152 rows: spending EUR = 12.5 + 20 (50% of 40) + 0 (excluded 100) = 32.5; USD = 0 (not mine). */
    private fun seedSchema152Rows(db: SQLiteDatabase) {
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (1, 'PURCHASE', 'EUR', 12.5)")
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, isSharedExpense, mySharePercentage) " +
                "VALUES (2, 'PURCHASE', 'EUR', 40.0, 1, 50)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (3, 'DEPOSIT', 'EUR', 1000.0)")
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, isNotMine) " +
                "VALUES (4, 'PURCHASE', 'USD', 9.5, 1)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (5, 'TRANSFER', 'EUR', 200.0)")
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, spendingExclusionReason) " +
                "VALUES (6, 'PURCHASE', 'EUR', 100.0, 'FIXTURE_EXCLUSION')"
        )
    }

    private fun bucket(n: Int, s: String, vararg key: String) = AggregateBucket(key.toList(), n, 0, BigDecimal(s))

    private fun entry(id: SemanticAggregateId, vararg buckets: AggregateBucket) =
        AggregateEntry(SemanticAggregates.definitionHash(id), buckets.toList())

    /** Manifest set for the schema-152 rows; the spending entry carries [spendingHash] and [spendingBuckets]. */
    private fun schema152Set(
        spendingHash: String,
        spendingBuckets: List<AggregateBucket> = listOf(bucket(3, "32.5", "EUR"), bucket(1, "0", "USD"))
    ): SemanticAggregateSet = SemanticAggregateSet.ofCaptured(
        mapOf(
            SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY to entry(
                SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                bucket(1, "1000", "DEPOSIT", "EUR"),
                bucket(3, "152.5", "PURCHASE", "EUR"),
                bucket(1, "9.5", "PURCHASE", "USD"),
                bucket(1, "200", "TRANSFER", "EUR")
            ),
            spending to AggregateEntry(spendingHash, spendingBuckets),
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY to entry(
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                bucket(1, "1000", "EUR")
            ),
            SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY to entry(
                SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY,
                bucket(1, "15", "0", "USD"),
                bucket(1, "9", "1", "EUR")
            )
        )
    )

    private fun expectation(set: SemanticAggregateSet) =
        SemanticExpectation(SemanticCheckpoint.C1_STAGED_SOURCE, SemanticBasis.Compare(set))

    private inline fun <T> withDb(withRefundNettedColumn: Boolean, seed: (SQLiteDatabase) -> Unit, block: (SQLiteDatabase) -> T): T {
        val db = SQLiteDatabase.create(null)
        try {
            createTables(db, withRefundNettedColumn)
            seed(db)
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun assertMismatchOnSpending(result: SemanticCheckResult) {
        assertTrue("expected Failed but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Failed)
        result as SemanticCheckResult.Failed
        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(spending, result.aggregateId)
    }

    // ── Hash control ─────────────────────────────────────────────

    @Test
    fun `INV10 the legacy hash used here is accepted and differs from the current spending hash`() {
        assertTrue(SemanticAggregates.isAcceptedDefinitionHash(spending, legacyHash))
        assertTrue(legacyHash != SemanticAggregates.definitionHash(spending))
    }

    // ── Evaluate on a schema-152 table ───────────────────────────

    @Test
    fun `INV10 legacy-hash manifest with old-formula values verifies on a schema-152 table`() {
        withDb(withRefundNettedColumn = false, seed = ::seedSchema152Rows) { db ->
            val result = SemanticAggregates.verifyAgainst(db, expectation(schema152Set(legacyHash)))
            assertTrue("expected Verified but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Verified)
        }
    }

    @Test
    fun `INV10 legacy-hash manifest with wrong values on a schema-152 table is a mismatch not a query failure`() {
        withDb(withRefundNettedColumn = false, seed = ::seedSchema152Rows) { db ->
            val wrong = schema152Set(legacyHash, listOf(bucket(3, "33.5", "EUR"), bucket(1, "0", "USD")))
            assertMismatchOnSpending(SemanticAggregates.verifyAgainst(db, expectation(wrong)))

            val missingBucket = schema152Set(legacyHash, listOf(bucket(3, "32.5", "EUR")))
            assertMismatchOnSpending(SemanticAggregates.verifyAgainst(db, expectation(missingBucket)))
        }
    }

    @Test
    fun `INV10 an unknown spending hash on a schema-152 table is DEFINITION_CHANGED and never compared`() {
        withDb(withRefundNettedColumn = false, seed = ::seedSchema152Rows) { db ->
            listOf("0000000000000000", legacyHash.dropLast(1) + "x").forEach { hash ->
                // Wrong values too: an unrecognised definition must not be reported as a mismatch.
                val set = schema152Set(hash, listOf(bucket(9, "1", "ZZZ")))
                val result = SemanticAggregates.verifyAgainst(db, expectation(set))
                assertTrue("hash $hash gave ${result.javaClass.simpleName}", result is SemanticCheckResult.NotVerifiable)
                assertEquals(
                    SemanticNotVerifiableReason.DEFINITION_CHANGED,
                    (result as SemanticCheckResult.NotVerifiable).reason
                )
            }
        }
    }

    // ── Current vs legacy definition on a schema-153 table ───────

    /** Row 7 has refundNettedAmount 30 of 100: current formula gives 70, the legacy formula gives 100. */
    private fun seedSchema153RefundedRow(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO expenses (id, transactionType, currency, amount, refundNettedAmount) " +
                "VALUES (1, 'PURCHASE', 'EUR', 100.0, 30.0)"
        )
        db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (2, 'DEPOSIT', 'EUR', 1000.0)")
    }

    private fun schema153Set(spendingHash: String, spendingSum: String): SemanticAggregateSet =
        SemanticAggregateSet.ofCaptured(
            mapOf(
                SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY to entry(
                    SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
                    bucket(1, "1000", "DEPOSIT", "EUR"),
                    bucket(1, "100", "PURCHASE", "EUR")
                ),
                spending to AggregateEntry(spendingHash, listOf(bucket(1, spendingSum, "EUR"))),
                SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY to entry(
                    SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
                    bucket(1, "1000", "EUR")
                ),
                SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY to entry(
                    SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY,
                    bucket(1, "15", "0", "USD"),
                    bucket(1, "9", "1", "EUR")
                )
            )
        )

    @Test
    fun `INV10 a current-hash manifest on a schema-153 table uses the current netting definition`() {
        withDb(withRefundNettedColumn = true, seed = ::seedSchema153RefundedRow) { db ->
            val currentHash = SemanticAggregates.definitionHash(spending)
            // Control: the netted value (100 - 30) with the current hash verifies.
            val ok = SemanticAggregates.verifyAgainst(db, expectation(schema153Set(currentHash, "70")))
            assertTrue("expected Verified but was ${ok.javaClass.simpleName}", ok is SemanticCheckResult.Verified)

            // Failing boundary: the un-netted value with the current hash is a mismatch (legacy SQL not used).
            assertMismatchOnSpending(SemanticAggregates.verifyAgainst(db, expectation(schema153Set(currentHash, "100"))))
        }
    }

    @Test
    fun `INV10 a legacy-hash manifest on a schema-153 table is queried with the legacy definition`() {
        withDb(withRefundNettedColumn = true, seed = ::seedSchema153RefundedRow) { db ->
            // The legacy definition ignores refundNettedAmount: 100 verifies, the netted 70 does not.
            val legacyOk = SemanticAggregates.verifyAgainst(db, expectation(schema153Set(legacyHash, "100")))
            assertTrue("expected Verified but was ${legacyOk.javaClass.simpleName}", legacyOk is SemanticCheckResult.Verified)
            assertMismatchOnSpending(SemanticAggregates.verifyAgainst(db, expectation(schema153Set(legacyHash, "70"))))
        }
    }

    // ── BackupVerifier.verifyQuick on a staged schema-152 file ───

    private fun stagedFile(): File {
        val file = File.createTempFile("s2a-legacy-", ".db")
        val seedDb = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            createTables(seedDb, withRefundNettedColumn = false)
            seedSchema152Rows(seedDb)
        } finally {
            seedDb.close()
        }
        return file
    }

    @Test
    fun `INV10 verifyQuick of a staged schema-152 file with a legacy-hash manifest does not throw`() {
        val file = stagedFile()
        try {
            val result = BackupVerifier.verifyQuick(file, emptyMap(), expectation(schema152Set(legacyHash)))
            assertTrue("expected Verified but was ${result?.javaClass?.simpleName}", result is SemanticCheckResult.Verified)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `INV10 verifyQuick of a staged schema-152 file fails closed with a mismatch on wrong legacy values`() {
        val file = stagedFile()
        try {
            val wrong = schema152Set(legacyHash, listOf(bucket(3, "33.5", "EUR"), bucket(1, "0", "USD")))
            val thrown = assertThrows(BackupVerifier.SemanticVerificationFailedException::class.java) {
                BackupVerifier.verifyQuick(file, emptyMap(), expectation(wrong))
            }
            // MISMATCH proves the legacy query ran; QUERY_FAILED would mean the current SQL hit the 152 table.
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, thrown.result.code)
            assertEquals(spending, thrown.result.aggregateId)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `INV10 verifyQuick of a staged schema-152 file with an unknown hash is NotVerifiable DEFINITION_CHANGED`() {
        val file = stagedFile()
        try {
            val result = BackupVerifier.verifyQuick(
                file, emptyMap(), expectation(schema152Set("0000000000000000"))
            )
            assertTrue(result is SemanticCheckResult.NotVerifiable)
            assertEquals(
                SemanticNotVerifiableReason.DEFINITION_CHANGED,
                (result as SemanticCheckResult.NotVerifiable).reason
            )
        } finally {
            file.delete()
        }
    }
}
