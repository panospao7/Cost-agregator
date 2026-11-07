package com.yourname.expensetracker.data.backup

import androidx.room.testing.MigrationTestHelper
import com.yourname.expensetracker.data.database.query.SpendingInclusionSql
import com.yourname.expensetracker.domain.backup.SemanticNotVerifiableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * refund-netting S2a, INV-10 of the semantic aggregates: refund netting changed the spending value
 * expression, so a backup manifest written by a pre-153 build carries an older SPENDING definition
 * hash. That hash must still be compared (the old and new expressions are equal on every row with
 * refundNettedAmount = 0.0), the current hash must be accepted, and any other hash must stay
 * DEFINITION_CHANGED. The other three aggregates must be unchanged.
 *
 * The expected hashes are rebuilt here from the frozen pre-153 definition TEXT written out as literals
 * (the on-disk manifest contract: first 16 hex characters of SHA-256 over
 * `v1|ID|table|keys=..|filter=..|value=..`), not from the production constants under test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesLegacyHashTest {

    @get:Rule
    val helper: MigrationTestHelper = RealSchemaFixture.newHelper()

    private val fixture = RealSchemaFixture

    // ── Frozen pre-153 definition texts (literals) ───────────────

    private val innerEffectiveAmount =
        "CASE WHEN isNotMine = 1 THEN 0.0 " +
            "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount " +
            "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 " +
            "ELSE amount END"

    private val pre153SpendingValue =
        "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 ELSE ($innerEffectiveAmount) END"

    private fun manifestHash(id: String, table: String, keys: String, filter: String, value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("v1|$id|$table|keys=$keys|filter=$filter|value=$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private val pre153SpendingHash = manifestHash(
        "SPENDING_EFFECTIVE_BY_CURRENCY", "expenses", "currency", "transactionType = 'PURCHASE'", pre153SpendingValue
    )

    // ── Helpers ──────────────────────────────────────────────────

    private fun fixtureFile(name: String, flagged: Boolean = false): File =
        fixture.createFixtureF(helper, name, withFlaggedPurchase = flagged, reverseInsertOrder = false)

    private fun expectation(
        set: SemanticAggregateSet,
        checkpoint: SemanticCheckpoint = SemanticCheckpoint.C1_STAGED_SOURCE
    ) = SemanticExpectation(checkpoint, SemanticBasis.Compare(set))

    private fun SemanticAggregateSet.withHash(id: SemanticAggregateId, hash: String): SemanticAggregateSet {
        val entry = aggregates.getValue(id)
        return SemanticAggregateSet(
            version, status, notCapturedReason,
            aggregates + (id to AggregateEntry(hash, entry.buckets))
        )
    }

    private fun SemanticAggregateSet.withSpendingBuckets(hash: String, buckets: List<AggregateBucket>): SemanticAggregateSet =
        SemanticAggregateSet(
            version, status, notCapturedReason,
            aggregates + (SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY to AggregateEntry(hash, buckets))
        )

    private fun verify(file: File, set: SemanticAggregateSet): SemanticCheckResult =
        fixture.readOnly(file) { db -> SemanticAggregates.verifyAgainst(db, expectation(set)) }

    // ── Hash contract ────────────────────────────────────────────

    @Test
    fun `INV10 the frozen pre-153 constant is the text that earlier builds hashed`() {
        assertEquals(pre153SpendingValue, SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING)
    }

    @Test
    fun `INV10 the spending definition hash changed and the other three are bit-for-bit unchanged`() {
        // Changed on purpose by refund netting: the new hash differs from the pre-153 one.
        assertNotEquals(pre153SpendingHash, SemanticAggregates.definitionHash(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY))

        // Unchanged: rebuilt from literal pre-153 definition text.
        assertEquals(
            manifestHash("EXPENSE_RAW_BY_TYPE_CURRENCY", "expenses", "transactionType,currency", "", "amount"),
            SemanticAggregates.definitionHash(SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY)
        )
        assertEquals(
            manifestHash(
                "INCOME_EFFECTIVE_BY_CURRENCY", "expenses", "currency", "transactionType = 'DEPOSIT'",
                innerEffectiveAmount
            ),
            SemanticAggregates.definitionHash(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY)
        )
        assertEquals(
            manifestHash(
                "RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY", "manual_recurring_expenses", "isActive,currency", "",
                "amount"
            ),
            SemanticAggregates.definitionHash(SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY)
        )
    }

    @Test
    fun `INV10 accepted hashes are the current one and for spending the pre-153 one and nothing else`() {
        val spending = SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY
        assertTrue(SemanticAggregates.isAcceptedDefinitionHash(spending, SemanticAggregates.definitionHash(spending)))
        assertTrue(SemanticAggregates.isAcceptedDefinitionHash(spending, pre153SpendingHash))
        // Failing boundaries: unrelated, near-miss and empty hashes are not accepted.
        listOf("0000000000000000", "ffffffffffffffff", pre153SpendingHash.dropLast(1) + "0", "", pre153SpendingHash + "0").forEach {
            if (it != pre153SpendingHash) {
                assertFalse("hash '$it' must not be accepted", SemanticAggregates.isAcceptedDefinitionHash(spending, it))
            }
        }
        // The legacy allowance is per aggregate: the spending legacy hash is not valid for the others.
        SemanticAggregateId.values().filter { it != spending }.forEach { id ->
            assertTrue(SemanticAggregates.isAcceptedDefinitionHash(id, SemanticAggregates.definitionHash(id)))
            assertFalse("$id must not accept the spending legacy hash", SemanticAggregates.isAcceptedDefinitionHash(id, pre153SpendingHash))
        }
    }

    @Test
    fun `INV10 the registry keeps the outer constant for spending and the inner one for income`() {
        assertEquals(SpendingInclusionSql.EFFECTIVE_AMOUNT, SemanticAggregates.definitionOf(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY).valueSql)
        assertEquals(innerEffectiveAmount, SemanticAggregates.definitionOf(SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY).valueSql)
    }

    // ── Verification against a real schema-153 database ──────────

    @Test
    fun `INV10 a pre-153 spending hash is accepted and the data is really compared`() {
        val file = fixtureFile("legacy-hash-accepted", flagged = true)
        val captured = fixture.capture(file)
        val spending = captured.aggregates.getValue(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY)

        // Control: the current hash is Verified.
        assertTrue(verify(file, captured) is SemanticCheckResult.Verified)

        // A manifest written by a pre-153 build: old hash, same data (every refundNettedAmount is 0.0).
        val legacy = captured.withHash(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, pre153SpendingHash)
        val accepted = verify(file, legacy)
        assertTrue("expected Verified but was ${accepted.javaClass.simpleName}", accepted is SemanticCheckResult.Verified)

        // Not skipped: with the legacy hash but a wrong bucket the comparison fails with a MISMATCH.
        val wrongBuckets = captured.withSpendingBuckets(
            pre153SpendingHash,
            spending.buckets.map { AggregateBucket(it.key, it.n, it.u, it.s + BigDecimal("1")) }
        )
        val mismatch = verify(file, wrongBuckets)
        assertTrue("expected Failed but was ${mismatch.javaClass.simpleName}", mismatch is SemanticCheckResult.Failed)
        mismatch as SemanticCheckResult.Failed
        assertEquals(SemanticCode.SEMANTIC_MISMATCH, mismatch.code)
        assertEquals(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, mismatch.aggregateId)
    }

    @Test
    fun `INV10 any other changed spending hash is still DEFINITION_CHANGED and never compared`() {
        val file = fixtureFile("legacy-hash-other")
        val captured = fixture.capture(file)

        listOf("0000000000000000", "ffffffffffffffff", pre153SpendingHash.dropLast(1) + "x").forEach { hash ->
            // Wrong data as well: a changed definition must not be reported as a mismatch.
            val stale = captured.withSpendingBuckets(hash, listOf(AggregateBucket(listOf("ZZZ"), 9, 0, BigDecimal.ONE)))
            val result = verify(file, stale)
            assertTrue("hash $hash gave ${result.javaClass.simpleName}", result is SemanticCheckResult.NotVerifiable)
            assertEquals(
                SemanticNotVerifiableReason.DEFINITION_CHANGED,
                (result as SemanticCheckResult.NotVerifiable).reason
            )
        }
    }

    @Test
    fun `INV10 the spending legacy hash on another aggregate is DEFINITION_CHANGED`() {
        val file = fixtureFile("legacy-hash-other-id")
        val captured = fixture.capture(file)

        listOf(
            SemanticAggregateId.INCOME_EFFECTIVE_BY_CURRENCY,
            SemanticAggregateId.EXPENSE_RAW_BY_TYPE_CURRENCY,
            SemanticAggregateId.RECURRING_RULE_AMOUNT_BY_ACTIVE_CURRENCY
        ).forEach { id ->
            val result = verify(file, captured.withHash(id, pre153SpendingHash))
            assertTrue("$id gave ${result.javaClass.simpleName}", result is SemanticCheckResult.NotVerifiable)
            assertEquals(
                SemanticNotVerifiableReason.DEFINITION_CHANGED,
                (result as SemanticCheckResult.NotVerifiable).reason
            )
        }
    }

    @Test
    fun `INV10 a restore verification of a pre-153 manifest passes with a Verified semantic result`() {
        val file = fixtureFile("legacy-hash-restore")
        val captured = fixture.capture(file)
        val counts = fixture.manifestCounts(file)
        val legacy = captured.withHash(SemanticAggregateId.SPENDING_EFFECTIVE_BY_CURRENCY, pre153SpendingHash)

        val summary = BackupVerifier.verify(
            file, counts, expectation(legacy, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
        )

        assertTrue(summary.errors.toString(), summary.passed)
        assertTrue(
            "expected Verified but was ${summary.semanticResult}",
            summary.semanticResult is SemanticCheckResult.Verified
        )
    }
}
