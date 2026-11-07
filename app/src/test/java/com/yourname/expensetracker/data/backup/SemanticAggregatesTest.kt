package com.yourname.expensetracker.data.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.CancellationException

/**
 * UF-46 / S1 capture and compare on the REAL v152 schema (spec section 7: AE-2, 3, 4, 11, 14, 15, 17, 18;
 * invariants INV-2, 3, 4, 10, 13, 14, 16 and the capture bounds of INV-5).
 *
 * Expected values are hand-computed from the spec (fixture F, see [RealSchemaFixture]); no expectation is
 * derived by re-running production SQL. Each failing boundary is paired with a passing control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesTest {

    @get:Rule
    val helper: MigrationTestHelper = RealSchemaFixture.newHelper()

    private val fixture = RealSchemaFixture

    // ── Helpers ──────────────────────────────────────────────────

    private fun fixtureFile(name: String, flagged: Boolean = false, reverse: Boolean = false): File =
        fixture.createFixtureF(helper, name, withFlaggedPurchase = flagged, reverseInsertOrder = reverse)

    private fun compareExpectation(
        set: SemanticAggregateSet,
        checkpoint: SemanticCheckpoint = SemanticCheckpoint.C1_STAGED_SOURCE
    ) = SemanticExpectation(checkpoint, SemanticBasis.Compare(set))

    private fun SemanticAggregateSet.withEntry(id: SemanticAggregateId, entry: AggregateEntry): SemanticAggregateSet =
        SemanticAggregateSet(version, status, notCapturedReason, aggregates + (id to entry))

    private fun failed(result: SemanticCheckResult): SemanticCheckResult.Failed {
        assertTrue("expected Failed but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Failed)
        return result as SemanticCheckResult.Failed
    }

    private fun assertBoundedText(label: String, text: String, file: File? = null) {
        fixture.forbiddenValueText.forEach {
            assertFalse("$label must not contain '$it': $text", text.contains(it))
        }
        listOf(
            "DROP", "DELETE", "FROM", "WHERE", "no such column", "isActive",
            "manual_recurring_expenses", "spendingExclusionReason", "32.5", "9.99"
        ).forEach { assertFalse("$label must not contain '$it': $text", text.contains(it)) }
        if (file != null) {
            assertFalse("$label must not contain the path", text.contains(file.absolutePath))
            assertFalse("$label must not contain the file name", text.contains(file.name))
        }
    }

    // ── AE-2: registry definitions on fixture F ──────────────────

    @Test
    fun AE2_capture_of_fixture_F_matches_the_hand_computed_aggregates() {
        val file = fixtureFile("ae2-fixture-f")

        val (set, counts) = fixture.readOnly(file) { db ->
            val counts = fixture.tableCounts(db)
            SemanticAggregates.capture(db, counts) to counts
        }

        fixture.assertFixtureF(set)
        // Spec AE-2 spelled out: SPENDING EUR n=2 s=32.5, INCOME EUR n=1 s=1000, sum of n = 6 = row count.
        val spending = set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        assertEquals(2, spending.n)
        assertEquals(0, BigDecimal("32.5").compareTo(spending.s))
        val income = set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.single()
        assertEquals(listOf("EUR"), income.key)
        assertEquals(1, income.n)
        assertEquals(0, BigDecimal("1000").compareTo(income.s))
        assertEquals(6, counts["expenses"])
        assertEquals(6, set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.sumOf { it.n })
        // Every entry carries the current definition hash of its own id.
        SemanticAggregateId.values().forEach { id ->
            assertEquals(SemanticAggregates.definitionHash(id), set.aggregates.getValue(id).definitionHash)
        }
    }

    @Test
    fun AE2_conservation_violation_degrades_to_not_captured_and_the_matching_count_is_the_control() {
        val file = fixtureFile("ae2-conservation")

        fixture.readOnly(file) { db ->
            val good = fixture.tableCounts(db)
            assertEquals(SemanticCaptureStatus.CAPTURED, SemanticAggregates.capture(db, good).status)

            listOf(
                "expenses one too many" to good + ("expenses" to good.getValue("expenses") + 1),
                "expenses one too few" to good + ("expenses" to good.getValue("expenses") - 1),
                "recurring one too many" to
                    good + ("manual_recurring_expenses" to good.getValue("manual_recurring_expenses") + 1),
                "expenses count unavailable" to (good - "expenses"),
                "recurring count unavailable" to (good - "manual_recurring_expenses")
            ).forEach { (label, counts) ->
                val set = SemanticAggregates.capture(db, counts)
                assertEquals(label, SemanticCaptureStatus.NOT_CAPTURED, set.status)
                assertEquals(label, SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED, set.notCapturedReason)
                assertTrue("$label: a partial CAPTURED section is never produced", set.aggregates.isEmpty())
            }
        }
    }

    @Test
    fun AE2_spending_is_purchase_only_and_no_other_type_drops_out_of_the_raw_aggregate() {
        val file = fixtureFile("ae2-types")
        fixture.mutate(
            file,
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) " +
                "VALUES (50, 7.0, 'EUR', 'M50', 20000, 'WITHDRAWAL', 0)",
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) " +
                "VALUES (51, 3.0, 'EUR', 'M51', 20000, 'purchase', 0)"
        )

        val set = fixture.capture(file)

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        assertEquals(8, raw.buckets.sumOf { it.n })
        assertEquals(1, raw.buckets.single { it.key == listOf("WITHDRAWAL", "EUR") }.n)
        assertEquals(1, raw.buckets.single { it.key == listOf("purchase", "EUR") }.n)
        // Spending and income are unchanged: WITHDRAWAL and a lower-case type are not PURCHASE.
        fixture.assertBuckets(
            "spending", set.aggregates[SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY],
            fixture.expectedBuckets(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
        )
        fixture.assertBuckets(
            "income", set.aggregates[SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY],
            fixture.expectedBuckets(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)
        )
    }

    // ── AE-2 / D-SIS: the spending-exclusion flag ────────────────

    @Test
    fun AE2_flagged_purchase_row_increments_n_and_leaves_the_spending_sum_unchanged() {
        val plain = fixture.capture(fixtureFile("ae2-flag-off"))
        val flagged = fixture.capture(fixtureFile("ae2-flag-on", flagged = true))

        fixture.assertFixtureF(plain)
        fixture.assertFixtureF(flagged, withFlaggedPurchase = true)
        val plainSpending = plain.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        val flaggedSpending = flagged.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        assertEquals(plainSpending.n + 1, flaggedSpending.n)
        assertEquals("flagged row adds 0 to s", 0, plainSpending.s.compareTo(flaggedSpending.s))
        // The raw aggregate ignores the flag entirely: 100.0 is counted there.
        val flaggedRaw = flagged.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
            .buckets.single { it.key == listOf("PURCHASE", "EUR") }
        assertEquals(3, flaggedRaw.n)
        assertEquals(0, BigDecimal("152.5").compareTo(flaggedRaw.s))
    }

    @Test
    fun AE2_dropping_the_spending_exclusion_flag_changes_the_sum_and_is_a_spending_mismatch() {
        val file = fixtureFile("ae2-flag-drop", flagged = true)
        val before = fixture.capture(file)
        // Control: re-capturing the unchanged database compares equal.
        assertTrue(
            SemanticAggregates.compare(before, fixture.capture(file), SemanticCheckpoint.C1_STAGED_SOURCE)
                is SemanticCheckResult.Verified
        )

        fixture.mutate(file, "UPDATE expenses SET spendingExclusionReason = NULL WHERE id = 7")
        val after = fixture.capture(file)

        val spendingEur = after.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        assertEquals(3, spendingEur.n)
        assertEquals("100.0 now counts in the sum", 0, BigDecimal("132.5").compareTo(spendingEur.s))
        val result = failed(SemanticAggregates.compare(before, after, SemanticCheckpoint.C2_STAGED_POST_MIGRATION))
        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        // The raw aggregate cannot see the flag, so the first divergence is the spending one.
        assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, result.aggregateId)
        assertEquals(1, result.mismatchedBuckets)
        assertEquals(SemanticCheckpoint.C2_STAGED_POST_MIGRATION, result.checkpoint)
    }

    @Test
    fun AE2_income_ignores_the_spending_exclusion_flag_but_honours_is_not_mine() {
        val file = fixtureFile("ae2-income-flag")
        val before = fixture.capture(file)

        // Flag the DEPOSIT row: income uses the plain effective amount, so nothing moves.
        fixture.mutate(file, "UPDATE expenses SET spendingExclusionReason = '${fixture.EXCLUSION_REASON}' WHERE id = 3")
        val flagged = fixture.capture(file)
        assertEquals(before, flagged)
        fixture.assertBuckets(
            "income", flagged.aggregates[SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY],
            fixture.expectedBuckets(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)
        )

        // Boundary: isNotMine on the same DEPOSIT row does zero the income sum (n stays 1).
        fixture.mutate(file, "UPDATE expenses SET isNotMine = 1 WHERE id = 3")
        val notMine = fixture.capture(file)
        val income = notMine.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.single()
        assertEquals(1, income.n)
        assertEquals(0, BigDecimal.ZERO.compareTo(income.s))
        assertEquals(
            SemanticCode.SEMANTIC_MISMATCH,
            failed(SemanticAggregates.compare(before, notMine, SemanticCheckpoint.C1_STAGED_SOURCE)).code
        )
    }

    // ── AE-3: not-mine rows, absent vs zero, no currency mixing ──

    @Test
    fun AE3_not_mine_purchase_is_a_present_zero_bucket_distinct_from_an_absent_bucket() {
        val file = fixtureFile("ae3-not-mine")
        val set = fixture.capture(file)

        val spending = set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
        val usd = spending.buckets.single { it.key == listOf("USD") }
        assertEquals(1, usd.n)
        assertEquals(0, usd.u)
        assertEquals(0, BigDecimal.ZERO.compareTo(usd.s))
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        assertNotNull(raw.buckets.singleOrNull { it.key == listOf("PURCHASE", "USD") })
        assertNotNull(raw.buckets.singleOrNull { it.key == listOf("UNKNOWN", "GBP") })
        val recurring = set.aggregates.getValue(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY)
        assertEquals(setOf(listOf("1", "EUR"), listOf("0", "USD")), recurring.buckets.map { it.key }.toSet())

        // Control: the set equals itself. Boundary: the same set without the zero bucket is a mismatch.
        assertTrue(
            SemanticAggregates.compare(set, set, SemanticCheckpoint.C1_STAGED_SOURCE) is SemanticCheckResult.Verified
        )
        val withoutUsd = set.withEntry(
            SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
            AggregateEntry(spending.definitionHash, spending.buckets.filter { it.key != listOf("USD") })
        )
        val result = failed(SemanticAggregates.compare(set, withoutUsd, SemanticCheckpoint.C1_STAGED_SOURCE))
        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, result.aggregateId)
        assertEquals(1, result.mismatchedBuckets)
    }

    @Test
    fun AE3_money_buckets_are_per_raw_currency_text_and_case_sensitive() {
        val file = fixtureFile("ae3-currency-text")
        fixture.mutate(
            file,
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType) " +
                "VALUES (60, 1.0, 'eur', 'M60', 20000, 'PURCHASE')",
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType) " +
                "VALUES (61, 2.0, '', 'M61', 20000, 'PURCHASE')",
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType) " +
                "VALUES (62, 4.0, 'XXX', 'M62', 20000, 'PURCHASE')"
        )

        val set = fixture.capture(file)

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        val spending = set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
        assertEquals(
            setOf(listOf("EUR"), listOf("USD"), listOf("eur"), listOf(""), listOf("XXX")),
            spending.buckets.map { it.key }.toSet()
        )
        // One currency per money bucket, and the lower-case bucket never merged into EUR.
        spending.buckets.forEach { assertEquals(1, it.key.size) }
        assertEquals(2, spending.buckets.single { it.key == listOf("EUR") }.n)
        assertEquals(0, BigDecimal("32.5").compareTo(spending.buckets.single { it.key == listOf("EUR") }.s))
        assertEquals(0, BigDecimal("1").compareTo(spending.buckets.single { it.key == listOf("eur") }.s))
        assertEquals(0, BigDecimal("4").compareTo(spending.buckets.single { it.key == listOf("XXX") }.s))
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        raw.buckets.forEach { assertEquals(2, it.key.size) }
        assertEquals(9, raw.buckets.sumOf { it.n })
    }

    @Test
    fun AE3_empty_tables_capture_as_no_buckets_which_is_not_equal_to_a_populated_set() {
        val emptyFile = fixture.createSeeded(helper, "ae3-empty") { }
        val populated = fixture.capture(fixtureFile("ae3-populated"))

        val empty = fixture.capture(emptyFile)

        assertEquals(SemanticCaptureStatus.CAPTURED, empty.status)
        SemanticAggregateId.values().forEach { id ->
            assertTrue("$id has no buckets for empty tables", empty.aggregates.getValue(id).buckets.isEmpty())
        }
        // Control: empty verifies against an empty restored table.
        assertTrue(
            SemanticAggregates.compare(empty, empty, SemanticCheckpoint.C1_STAGED_SOURCE)
                is SemanticCheckResult.Verified
        )
        // Boundaries: an empty expectation never matches data, and data never matches an empty expectation.
        fixture.readOnly(emptyFile) { db ->
            assertEquals(
                SemanticCode.SEMANTIC_MISMATCH,
                failed(SemanticAggregates.verifyAgainst(db, compareExpectation(populated))).code
            )
        }
        fixture.readOnly(fixtureFile("ae3-populated-2")) { db ->
            assertEquals(
                SemanticCode.SEMANTIC_MISMATCH,
                failed(SemanticAggregates.verifyAgainst(db, compareExpectation(empty))).code
            )
        }
    }

    // ── AE-4: exact value semantics ──────────────────────────────

    @Test
    fun AE4_sums_and_bucket_order_are_identical_for_two_row_orders() {
        val forward = fixture.capture(fixtureFile("ae4-order-forward"))
        val reversed = fixture.capture(fixtureFile("ae4-order-reversed", reverse = true))

        assertEquals(forward, reversed)
        assertEquals(
            "serialised form (bucket order included) is deterministic",
            forward.toJson().toString(),
            reversed.toJson().toString()
        )
        fixture.assertFixtureF(reversed)
    }

    @Test
    fun AE4_purchase_usd_sum_is_the_exact_binary_expansion_of_9_99() {
        val set = fixture.capture(fixtureFile("ae4-exact-9-99"))

        val bucket = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
            .buckets.single { it.key == listOf("PURCHASE", "USD") }
        assertEquals(BigDecimal(9.99).toPlainString(), bucket.s.toPlainString())
        assertNotEquals("the sum is the binary expansion, not the decimal literal", "9.99", bucket.s.toPlainString())
    }

    @Test
    fun AE4_sums_are_exact_and_never_rounded() {
        val file = fixture.createSeeded(helper, "ae4-exact-sum") { db ->
            fixture.insertExpense(db, 1, "PURCHASE", "EUR", "0.1")
            fixture.insertExpense(db, 2, "PURCHASE", "EUR", "0.2")
        }

        val set = fixture.capture(file)

        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.single()
        assertEquals(0, BigDecimal(0.1).add(BigDecimal(0.2)).compareTo(raw.s))
        assertNotEquals("0.1 + 0.2 is not rounded to 0.3", 0, BigDecimal("0.3").compareTo(raw.s))
    }

    @Test
    fun AE4_changing_one_amount_by_a_billionth_is_a_mismatch_with_an_identical_control() {
        val file = fixtureFile("ae4-billionth")
        val expected = fixture.capture(file)
        assertTrue(
            "control: unchanged data verifies",
            fixture.readOnly(file) { db -> SemanticAggregates.verifyAgainst(db, compareExpectation(expected)) }
                is SemanticCheckResult.Verified
        )

        fixture.mutate(file, "UPDATE expenses SET amount = 12.500000001 WHERE id = 1")
        val result = failed(
            fixture.readOnly(file) { db -> SemanticAggregates.verifyAgainst(db, compareExpectation(expected)) }
        )

        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, result.aggregateId)
        assertEquals(1, result.mismatchedBuckets)
    }

    @Test
    fun AE4_a_non_finite_amount_counts_in_u_and_never_in_s() {
        val file = fixtureFile("ae4-infinite")
        // 1e999 overflows a REAL to +Infinity, which the real NOT NULL column accepts.
        fixture.mutate(file, "UPDATE expenses SET amount = 1e999 WHERE id = 1")

        val set = fixture.capture(file)

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        val rawEur = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
            .buckets.single { it.key == listOf("PURCHASE", "EUR") }
        assertEquals(2, rawEur.n)
        assertEquals(1, rawEur.u)
        assertEquals(0, BigDecimal("40").compareTo(rawEur.s))
        val spendingEur = set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }
        assertEquals(2, spendingEur.n)
        assertEquals(1, spendingEur.u)
        assertEquals(0, BigDecimal("20").compareTo(spendingEur.s))
        // The other buckets stay free of unavailable values.
        set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.forEach {
            assertEquals(0, it.u)
        }
    }

    @Test
    fun AE4_a_null_amount_counts_in_u_and_never_in_s_on_a_relaxed_table() {
        // The real columns are NOT NULL, so NULL can only arise in a damaged copy. A relaxed in-memory
        // table with the same column names models that copy.
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                "CREATE TABLE expenses (id INTEGER PRIMARY KEY, transactionType TEXT NOT NULL, currency TEXT NOT NULL, " +
                    "amount REAL, isNotMine INTEGER NOT NULL DEFAULT 0, isSharedExpense INTEGER NOT NULL DEFAULT 0, " +
                    "myShareAmount REAL, mySharePercentage INTEGER, spendingExclusionReason TEXT DEFAULT NULL, " +
                    "refundNettedAmount REAL NOT NULL DEFAULT 0.0)"
            )
            db.execSQL(
                "CREATE TABLE manual_recurring_expenses (id INTEGER PRIMARY KEY, isActive INTEGER NOT NULL, " +
                    "currency TEXT NOT NULL, amount REAL)"
            )
            db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (1, 'PURCHASE', 'EUR', NULL)")
            db.execSQL("INSERT INTO expenses (id, transactionType, currency, amount) VALUES (2, 'PURCHASE', 'EUR', 10.0)")
            db.execSQL("INSERT INTO manual_recurring_expenses (id, isActive, currency, amount) VALUES (1, 1, 'EUR', NULL)")

            val set = SemanticAggregates.capture(db, mapOf("expenses" to 2, "manual_recurring_expenses" to 1))

            assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
            val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY).buckets.single()
            assertEquals(2, raw.n)
            assertEquals(1, raw.u)
            assertEquals(0, BigDecimal("10").compareTo(raw.s))
            val spending = set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY).buckets.single()
            assertEquals(2, spending.n)
            assertEquals(1, spending.u)
            val recurring = set.aggregates.getValue(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY)
                .buckets.single()
            assertEquals(listOf("1", "EUR"), recurring.key)
            assertEquals(1, recurring.n)
            assertEquals(1, recurring.u)
            assertEquals(0, BigDecimal.ZERO.compareTo(recurring.s))
        } finally {
            db.close()
        }
    }

    // ── AE-11 / INV-10: definition hash ──────────────────────────

    @Test
    fun AE11_wrong_spending_hash_is_definition_changed_never_a_mismatch_and_the_restore_proceeds() {
        val file = fixtureFile("ae11-hash")
        val captured = fixture.capture(file)
        val counts = fixture.manifestCounts(file)

        // Control: all hashes right -> Verified, restore verification passes.
        val control = BackupVerifier.verify(file, counts, compareExpectation(captured, SemanticCheckpoint.C2_STAGED_POST_MIGRATION))
        assertTrue(control.errors.toString(), control.passed)
        assertTrue(control.semanticResult is SemanticCheckResult.Verified)

        // Wrong SPENDING hash AND wrong SPENDING data: still not a mismatch.
        val spending = captured.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
        val staleSpending = captured.withEntry(
            SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
            AggregateEntry("0000000000000000", listOf(AggregateBucket(listOf("ZZZ"), 9, 0, BigDecimal("1"))))
        )
        assertNotEquals(spending.definitionHash, "0000000000000000")
        val summary = BackupVerifier.verify(
            file, counts, compareExpectation(staleSpending, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
        )

        assertTrue(summary.errors.toString(), summary.passed)
        val result = summary.semanticResult
        assertTrue("expected NotVerifiable but was $result", result is SemanticCheckResult.NotVerifiable)
        assertEquals(SemanticNotVerifiableReason.DEFINITION_CHANGED, (result as SemanticCheckResult.NotVerifiable).reason)
        assertEquals(SemanticCheckpoint.C2_STAGED_POST_MIGRATION, result.checkpoint)
        assertEquals(
            SemanticCode.SEMANTIC_NOT_VERIFIABLE_DEFINITION_CHANGED,
            SemanticCode.forNotVerifiable(result.reason)
        )
    }

    @Test
    fun AE11_a_changed_definition_does_not_mask_a_real_mismatch_in_another_aggregate() {
        val file = fixtureFile("ae11-no-mask")
        val captured = fixture.capture(file)
        val staleSpending = captured.withEntry(
            SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY,
            AggregateEntry("ffffffffffffffff", emptyList())
        )
        fixture.mutate(file, "UPDATE expenses SET amount = 999.0 WHERE id = 3")

        val result = failed(
            fixture.readOnly(file) { db -> SemanticAggregates.verifyAgainst(db, compareExpectation(staleSpending)) }
        )

        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, result.aggregateId)
    }

    @Test
    fun AE11_an_aggregate_declared_definition_changed_is_not_compared_even_when_its_data_differs() {
        val file = fixtureFile("ae11-declared")
        val captured = fixture.capture(file)
        // Income data differs from the expectation, and the basis declares it invalidated.
        val wrongIncome = captured.withEntry(
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
            AggregateEntry(
                SemanticAggregates.definitionHash(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY),
                listOf(AggregateBucket(listOf("EUR"), 1, 0, BigDecimal("1")))
            )
        )

        fixture.readOnly(file) { db ->
            val declared = SemanticAggregates.verifyAgainst(
                db,
                SemanticExpectation(
                    SemanticCheckpoint.C3_LIVE_POST_SWAP,
                    SemanticBasis.Compare(wrongIncome, setOf(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY))
                )
            )
            assertTrue(declared is SemanticCheckResult.NotVerifiable)
            assertEquals(
                SemanticNotVerifiableReason.DEFINITION_CHANGED,
                (declared as SemanticCheckResult.NotVerifiable).reason
            )
            // Control: without the declaration the same wrong data is a mismatch.
            val undeclared = failed(SemanticAggregates.verifyAgainst(db, compareExpectation(wrongIncome)))
            assertEquals(SemanticCode.SEMANTIC_MISMATCH, undeclared.code)
            assertEquals(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY, undeclared.aggregateId)
        }
    }

    // ── AE-14 / INV-13: query failure and cancellation ───────────

    private fun replaceRecurringWithoutIsActive(file: File) {
        fixture.mutate(
            file,
            "CREATE TABLE manual_recurring_expenses_x (id INTEGER PRIMARY KEY, merchant TEXT NOT NULL, " +
                "amount REAL NOT NULL, currency TEXT NOT NULL)",
            "INSERT INTO manual_recurring_expenses_x SELECT id, merchant, amount, currency FROM manual_recurring_expenses",
            "DROP TABLE manual_recurring_expenses",
            "ALTER TABLE manual_recurring_expenses_x RENAME TO manual_recurring_expenses"
        )
    }

    @Test
    fun AE14_missing_recurring_isActive_column_is_query_failed_and_fails_verify_closed() {
        val file = fixtureFile("ae14-missing-column")
        val captured = fixture.capture(file)
        val counts = fixture.manifestCounts(file)
        // Control: the intact database verifies.
        assertTrue(BackupVerifier.verify(file, counts, compareExpectation(captured)).passed)

        replaceRecurringWithoutIsActive(file)
        val summary = BackupVerifier.verify(file, counts, compareExpectation(captured))

        assertFalse(summary.passed)
        val result = failed(summary.semanticResult!!)
        assertEquals(SemanticCode.SEMANTIC_QUERY_FAILED, result.code)
        assertEquals(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY, result.aggregateId)
        assertEquals(0, result.mismatchedBuckets)
        val issue = summary.errors.single()
        assertTrue(issue, issue.startsWith("SEMANTIC_QUERY_FAILED"))
        assertBoundedText("issue", issue, file)
    }

    @Test
    fun AE14_verifyQuick_throws_a_code_only_exception_for_a_query_failure() {
        val file = fixtureFile("ae14-quick")
        val captured = fixture.capture(file)
        val counts = fixture.manifestCounts(file)
        assertTrue(BackupVerifier.verifyQuick(file, counts, compareExpectation(captured)) is SemanticCheckResult.Verified)

        replaceRecurringWithoutIsActive(file)
        val error = assertThrows(BackupVerifier.SemanticVerificationFailedException::class.java) {
            BackupVerifier.verifyQuick(file, counts, compareExpectation(captured))
        }

        assertEquals(SemanticCode.SEMANTIC_QUERY_FAILED, error.result.code)
        assertEquals("SEMANTIC_QUERY_FAILED", error.message)
        assertNull(error.cause)
    }

    @Test
    fun AE14_a_database_older_than_the_exclusion_column_fails_spending_as_query_failed() {
        // A real v151 database has no spendingExclusionReason column. The raw aggregate (same rows) still
        // matches, so the first failure is the spending aggregate, and it is a query failure.
        val expected = fixture.capture(fixtureFile("ae14-expected-152"))
        val old = fixture.createFixtureF(helper, "ae14-v151", version = 151)

        val result = failed(
            fixture.readOnly(old) { db -> SemanticAggregates.verifyAgainst(db, compareExpectation(expected)) }
        )

        assertEquals(SemanticCode.SEMANTIC_QUERY_FAILED, result.code)
        assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, result.aggregateId)
    }

    @Test
    fun AE14_cancellation_is_rethrown_by_capture_and_by_verifyAgainst() {
        val expected = fixture.capture(fixtureFile("ae14-cancel"))
        val db = mockk<SQLiteDatabase>()
        every { db.rawQuery(any<String>(), isNull<Array<String>>()) } throws CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            SemanticAggregates.capture(db, mapOf("expenses" to 6, "manual_recurring_expenses" to 2))
        }
        assertThrows(CancellationException::class.java) {
            SemanticAggregates.verifyAgainst(db, compareExpectation(expected, SemanticCheckpoint.C2_STAGED_POST_MIGRATION))
        }
    }

    @Test
    fun AE14_a_non_cancellation_failure_is_bounded_and_never_a_pass() {
        val expected = fixture.capture(fixtureFile("ae14-boom"))
        val db = mockk<SQLiteDatabase>()
        every { db.rawQuery(any<String>(), isNull<Array<String>>()) } throws
            IllegalStateException("secret 12.5 EUR PURCHASE /data/user/0/file.db SELECT x")

        val captured = SemanticAggregates.capture(db, mapOf("expenses" to 6, "manual_recurring_expenses" to 2))
        val result = failed(SemanticAggregates.verifyAgainst(db, compareExpectation(expected)))

        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, captured.status)
        assertEquals(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED, captured.notCapturedReason)
        assertTrue(captured.aggregates.isEmpty())
        assertEquals(SemanticCode.SEMANTIC_QUERY_FAILED, result.code)
        assertBoundedText("captured", captured.toString())
        assertBoundedText("captured json", captured.toJson().toString())
        assertBoundedText("result", "${result.code.name} ${result.aggregateId?.name} ${result.checkpoint.name}")
    }

    // ── AE-15 / INV-14: bounded text ─────────────────────────────

    @Test
    fun AE15_value_types_render_bounded_text_without_sums_keys_or_currencies() {
        val set = fixture.capture(fixtureFile("ae15-rendering"))

        val renderings = mutableListOf(set.toString())
        set.aggregates.values.forEach { entry ->
            renderings += entry.toString()
            entry.buckets.forEach { renderings += it.toString() }
        }

        renderings.forEach { assertBoundedText("rendering", it) }
        assertEquals("AggregateBucket(n=2, u=0)", set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)
            .buckets.single { it.key == listOf("EUR") }.toString())
    }

    @Test
    fun AE15_every_failure_path_of_verify_carries_codes_and_counts_only() {
        val file = fixtureFile("ae15-failures")
        val captured = fixture.capture(file)
        val counts = fixture.manifestCounts(file)
        val texts = mutableListOf<String>()

        // Mismatch.
        fixture.mutate(file, "UPDATE expenses SET amount = 12.75 WHERE id = 1")
        BackupVerifier.verify(file, counts, compareExpectation(captured)).let {
            assertFalse(it.passed)
            texts += it.errors
        }
        // Malformed basis.
        BackupVerifier.verify(
            file, counts, SemanticExpectation(SemanticCheckpoint.C1_STAGED_SOURCE, SemanticBasis.Malformed())
        ).let {
            assertFalse(it.passed)
            texts += it.errors
        }
        // Query failure (the amount is restored first so the raw aggregate no longer mismatches).
        fixture.mutate(file, "UPDATE expenses SET amount = 12.5 WHERE id = 1")
        replaceRecurringWithoutIsActive(file)
        BackupVerifier.verify(file, counts, compareExpectation(captured)).let {
            assertFalse(it.passed)
            texts += it.errors
        }
        // Quick path exception text.
        val quick = assertThrows(BackupVerifier.SemanticVerificationFailedException::class.java) {
            BackupVerifier.verifyQuick(file, counts, compareExpectation(captured))
        }
        texts += quick.message.orEmpty()

        assertEquals(4, texts.size)
        assertTrue(texts[0], texts[0].startsWith("SEMANTIC_MISMATCH"))
        assertTrue(texts[1], texts[1].startsWith("SEMANTIC_MANIFEST_MALFORMED"))
        assertTrue(texts[2], texts[2].startsWith("SEMANTIC_QUERY_FAILED"))
        assertEquals("SEMANTIC_QUERY_FAILED", texts[3])
        texts.forEach { assertBoundedText("failure text", it, file) }
        // Every issue line is exactly: CODE: aggregate=ID checkpoint=CP mismatchedBuckets=N (or the bare exception code).
        texts.take(3).forEach { text ->
            assertTrue(
                text,
                Regex("^SEMANTIC_[A-Z_]+: aggregate=[A-Z_]+ checkpoint=C[0-3]_[A-Z_]+ mismatchedBuckets=[0-9]+$").matches(text)
            )
        }
    }

    @Test
    fun AE15_hostile_currency_text_in_the_data_is_never_echoed_or_executed() {
        val file = fixtureFile("ae15-hostile")
        val captured = fixture.capture(file)
        fixture.mutate(
            file,
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType) " +
                "VALUES (70, 1.0, 'EUR''; DROP TABLE expenses;--', 'M70', 20000, 'PURCHASE')"
        )
        val counts = fixture.manifestCounts(file)

        val summary = BackupVerifier.verify(file, counts, compareExpectation(captured))

        assertFalse(summary.passed)
        val result = failed(summary.semanticResult!!)
        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, result.aggregateId)
        assertEquals(1, result.mismatchedBuckets)
        summary.errors.forEach { assertBoundedText("issue", it, file) }
        // The hostile text was only data: the table is intact.
        assertEquals(7, fixture.readOnly(file) { fixture.tableCounts(it).getValue("expenses") })
    }

    // ── AE-17 / INV-16: read-only ────────────────────────────────

    @Test
    fun AE17_capture_and_verifyAgainst_succeed_on_a_read_only_handle_and_never_write() {
        val file = fixtureFile("ae17-readonly")
        val bytesBefore = file.readBytes()

        fixture.readOnly(file) { db ->
            fun totalChanges(): Long =
                db.rawQuery("SELECT total_changes()", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getLong(0)
                }

            val changesBefore = totalChanges()
            val set = SemanticAggregates.capture(db, fixture.tableCounts(db))
            assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
            assertTrue(SemanticAggregates.verifyAgainst(db, compareExpectation(set)) is SemanticCheckResult.Verified)
            // The connection performed no row change while capturing and verifying.
            assertEquals("capture/verifyAgainst must not write", changesBefore, totalChanges())
        }

        // Neither entry point altered the database file.
        assertArrayEquals("capture/verifyAgainst must not write the database file", bytesBefore, file.readBytes())

        // Control: the byte comparison is able to see a write.
        fixture.mutate(file, "UPDATE expenses SET amount = 13.5 WHERE id = 1")
        assertFalse(bytesBefore.contentEquals(file.readBytes()))
    }

    @Test
    fun AE17_the_file_entry_points_leave_the_database_bytes_untouched() {
        val source = fixtureFile("ae17-bytes")
        val directory = Files.createTempDirectory("ae17-").toFile()
        val copy = File(directory, "copy.db")
        try {
            source.copyTo(copy, overwrite = true)
            val captured = fixture.capture(copy)
            val counts = fixture.manifestCounts(copy)
            val bytesBefore = copy.readBytes()
            val rowsBefore = fixture.readOnly(copy) { fixture.tableCounts(it) }

            val full = BackupVerifier.verify(copy, counts, compareExpectation(captured))
            val quick = BackupVerifier.verifyQuick(copy, counts, compareExpectation(captured))

            assertTrue(full.errors.toString(), full.passed)
            assertTrue(full.semanticResult is SemanticCheckResult.Verified)
            assertTrue(quick is SemanticCheckResult.Verified)
            assertArrayEquals("verifier must not write the database file", bytesBefore, copy.readBytes())
            assertEquals(rowsBefore, fixture.readOnly(copy) { fixture.tableCounts(it) })

            // Control: the byte comparison is able to see a write.
            fixture.mutate(copy, "UPDATE expenses SET amount = 13.5 WHERE id = 1")
            assertFalse(bytesBefore.contentEquals(copy.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }

    // ── AE-18 / INV-5 bounds: bucket limit and a large database ──

    private fun currencyDatabase(name: String, currencies: Int): File =
        fixture.createSeeded(helper, name) { db ->
            for (index in 0 until currencies) {
                fixture.insertExpense(db, index + 1L, "PURCHASE", "C%03d".format(index), "1.0")
            }
        }

    @Test
    fun AE18_257_distinct_currencies_degrade_to_not_captured_bucket_limit() {
        val file = currencyDatabase("ae18-257", 257)

        val set = fixture.capture(file)

        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, set.status)
        assertEquals(SemanticNotCapturedReason.SEMANTIC_BUCKET_LIMIT, set.notCapturedReason)
        assertTrue("no partial section is ever produced", set.aggregates.isEmpty())
        assertEquals(SemanticCode.SEMANTIC_BUCKET_LIMIT, set.notCapturedReason!!.code)
    }

    @Test
    fun AE18_256_distinct_currencies_is_the_inclusive_boundary_and_captures() {
        val file = currencyDatabase("ae18-256", 256)

        val set = fixture.capture(file)

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        assertNull(set.notCapturedReason)
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        assertEquals(256, raw.buckets.size)
        assertEquals(256, raw.buckets.sumOf { it.n })
        assertEquals(256, set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY).buckets.size)
    }

    @Test
    fun AE18_a_database_over_the_bucket_limit_cannot_match_a_captured_expectation() {
        val expected = fixture.capture(fixtureFile("ae18-expected"))
        val over = currencyDatabase("ae18-over", 257)

        val result = failed(
            fixture.readOnly(over) { db -> SemanticAggregates.verifyAgainst(db, compareExpectation(expected)) }
        )

        assertEquals(SemanticCode.SEMANTIC_MISMATCH, result.code)
        assertEquals(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY, result.aggregateId)
        assertTrue(result.mismatchedBuckets > 0)
    }

    @Test
    fun AE18_synthetic_large_database_is_captured_correctly_and_the_timing_is_recorded() {
        val rows = 6_000
        val currencies = listOf("EUR", "USD", "GBP", "CHF", "JPY")
        val file = fixture.createSeeded(helper, "ae18-large") { db ->
            for (index in 0 until rows) {
                val type = if (index % 4 == 0) "DEPOSIT" else "PURCHASE"
                fixture.insertExpense(db, index + 1L, type, currencies[index % currencies.size], "1.5")
            }
        }
        // Independent expectation: plain counting, 1.5 is exactly representable so n * 1.5 is exact.
        val expectedRows = HashMap<List<String>, Int>()
        for (index in 0 until rows) {
            val key = listOf(if (index % 4 == 0) "DEPOSIT" else "PURCHASE", currencies[index % currencies.size])
            expectedRows[key] = (expectedRows[key] ?: 0) + 1
        }

        val started = System.nanoTime()
        val set = fixture.capture(file)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("UF46_CAPTURE_TIMING rows=$rows buckets=${expectedRows.size} elapsedMs=$elapsedMs")

        assertEquals(SemanticCaptureStatus.CAPTURED, set.status)
        val raw = set.aggregates.getValue(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        assertEquals(expectedRows.keys, raw.buckets.map { it.key }.toSet())
        raw.buckets.forEach { bucket ->
            val n = expectedRows.getValue(bucket.key)
            assertEquals(n, bucket.n)
            assertEquals(0, bucket.u)
            assertEquals(0, BigDecimal("1.5").multiply(BigDecimal(n)).compareTo(bucket.s))
        }
        val incomeRows = expectedRows.filterKeys { it[0] == "DEPOSIT" }.values.sum()
        assertEquals(incomeRows, set.aggregates.getValue(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).buckets.sumOf { it.n })
        val spendingRows = expectedRows.filterKeys { it[0] == "PURCHASE" }.values.sum()
        assertEquals(spendingRows, set.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY).buckets.sumOf { it.n })
        assertEquals(rows, raw.buckets.sumOf { it.n })
    }
}
