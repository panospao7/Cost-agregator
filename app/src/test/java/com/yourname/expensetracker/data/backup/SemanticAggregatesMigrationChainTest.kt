package com.yourname.expensetracker.data.backup

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import com.yourname.expensetracker.data.database.DatabaseMigrations
import com.yourname.expensetracker.data.database.DatabaseSchemaPolicy
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

/**
 * UF-46 / S1, AE-10 and INV-9: the semantic aggregates are invariant across the REGISTERED migration chain.
 *
 * Part 1 enumerates the registered migration list (never a hard-coded one) and, for every start version
 * from 152 (the first schema that carries the spending-exclusion column) to the current schema, builds a
 * real database through `MigrationTestHelper`, migrates it to the current schema and requires that every
 * aggregate that is not explicitly declared as invalidated is unchanged. A migration that writes or
 * rebuilds a fingerprinted table without declaring it in
 * `SemanticAggregates.DECLARED_MIGRATION_INVALIDATIONS` therefore fails this test.
 *
 * When the current schema is 152 the chain is the single version 152 and the invariance reduces to
 * capture == expectedAggregatesAfterMigration (still asserted). Part 2 pins that a section written by a
 * pre-152 source (the D-SIS gate) is NOT_VERIFIABLE(VERSION).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SemanticAggregatesMigrationChainTest {

    @get:Rule
    val helper: MigrationTestHelper = RealSchemaFixture.newHelper()

    private val fixture = RealSchemaFixture
    private val current = DatabaseSchemaPolicy.CURRENT_VERSION

    private fun compareExpectation(
        basis: SemanticBasis,
        checkpoint: SemanticCheckpoint = SemanticCheckpoint.C2_STAGED_POST_MIGRATION
    ) = SemanticExpectation(checkpoint, basis)

    private fun migrateToCurrent(name: String, from: Int) {
        if (from < current) {
            helper.runMigrationsAndValidate(name, current, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS).close()
        }
    }

    // ── Part 1: enumerate the registered migrations ──────────────

    @Test
    fun AE10_registered_migrations_form_a_contiguous_chain_from_the_baseline_to_the_current_version() {
        val registered: List<Migration> = DatabaseMigrations.ALL.sortedBy { it.startVersion }

        assertTrue("migrations are registered", registered.isNotEmpty())
        assertEquals(DatabaseSchemaPolicy.MIGRATION_BASELINE, registered.first().startVersion)
        assertEquals(current, registered.last().endVersion)
        registered.zipWithNext().forEach { (left, right) ->
            assertEquals("gap or overlap after ${left.endVersion}", left.endVersion, right.startVersion)
        }
        registered.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
        assertTrue(
            "the policy exposes the same list as the builder",
            DatabaseSchemaPolicy.ALL_MIGRATIONS.toList() == DatabaseMigrations.ALL.toList()
        )
    }

    @Test
    fun AE10_declared_invalidations_name_only_registered_migration_end_versions() {
        val registeredEnds = DatabaseMigrations.ALL.map { it.endVersion }.toSet()

        SemanticAggregates.DECLARED_MIGRATION_INVALIDATIONS.forEach { (endVersion, ids) ->
            assertTrue("declared end version $endVersion is not a registered migration", endVersion in registeredEnds)
            assertTrue("declared end version $endVersion above the current schema", endVersion <= current)
            assertTrue("declared end version $endVersion names no aggregate", ids.isNotEmpty())
        }
    }

    @Test
    fun AE10_aggregates_are_invariant_across_the_migration_chain_from_every_start_version_152_to_current() {
        for (start in SemanticAggregates.MIN_SOURCE_SCHEMA_VERSION..current) {
            val name = "ae10-chain-$start"
            val file = fixture.createFixtureF(helper, name, version = start, withFlaggedPurchase = true)
            val before = fixture.capture(file)
            fixture.assertFixtureF(before, withFlaggedPurchase = true)

            migrateToCurrent(name, start)
            val after = fixture.capture(file)

            val basis = BackupVerifier.expectedAggregatesAfterMigration(before, start)
            assertTrue("start $start: expected a Compare basis but was $basis", basis is SemanticBasis.Compare)
            basis as SemanticBasis.Compare
            val declared = SemanticAggregates.DECLARED_MIGRATION_INVALIDATIONS
                .filter { (endVersion, _) -> endVersion in (start + 1)..current }
                .values.flatten().toSet()
            assertEquals("start $start: invalidated set is exactly the declared set", declared, basis.definitionChanged)

            SemanticAggregateId.values().filter { it !in declared }.forEach { id ->
                assertEquals(
                    "start $start: migration chain changed $id without a declared transform",
                    before.aggregates.getValue(id),
                    after.aggregates.getValue(id)
                )
            }
            if (declared.isEmpty()) {
                // capture == expectedAggregatesAfterMigration (the identity transform).
                assertEquals("start $start: capture equals the expectation", basis.set, after)
                assertEquals(before, after)
            }
            val result = fixture.readOnly(file) { db ->
                SemanticAggregates.verifyAgainst(db, compareExpectation(basis))
            }
            if (declared.isEmpty()) {
                assertTrue("start $start: expected Verified but was ${result.javaClass.simpleName}", result is SemanticCheckResult.Verified)
            } else {
                assertFalse("start $start: a declared invalidation must not fail", result is SemanticCheckResult.Failed)
            }
        }
    }

    @Test
    fun AE10_an_undeclared_write_to_a_fingerprinted_table_changes_the_capture_so_the_chain_check_would_fail() {
        // Sensitivity control for the invariance check above: each kind of write a migration could make to
        // a fingerprinted table is visible in the capture.
        val writes = listOf(
            "amount rewritten" to "UPDATE expenses SET amount = amount + 1 WHERE id = 1",
            "currency rewritten" to "UPDATE expenses SET currency = 'GBP' WHERE id = 1",
            "type rewritten" to "UPDATE expenses SET transactionType = 'WITHDRAWAL' WHERE id = 1",
            "row deleted" to "DELETE FROM expenses WHERE id = 5",
            "not-mine flag flipped" to "UPDATE expenses SET isNotMine = 1 WHERE id = 1",
            "exclusion flag set" to "UPDATE expenses SET spendingExclusionReason = '${fixture.EXCLUSION_REASON}' WHERE id = 1",
            "recurring deactivated" to "UPDATE manual_recurring_expenses SET isActive = 0 WHERE id = 1",
            "recurring amount rewritten" to "UPDATE manual_recurring_expenses SET amount = 10.0 WHERE id = 1"
        )
        writes.forEachIndexed { index, (label, statement) ->
            val file = fixture.createFixtureF(helper, "ae10-sensitivity-$index")
            val before = fixture.capture(file)

            fixture.mutate(file, statement)
            // A DELETE changes the row count, so the conservation counts are recomputed from the file.
            val after = fixture.capture(file)

            assertEquals("$label: the changed database still captures", SemanticCaptureStatus.CAPTURED, after.status)
            assertNotEquals("$label must change the capture", before, after)
            assertTrue(
                "$label must be reported as a mismatch",
                SemanticAggregates.compare(before, after, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
                    is SemanticCheckResult.Failed
            )
        }
    }

    @Test
    fun AE10_a_table_rebuild_that_preserves_every_row_does_not_change_the_capture() {
        val file = fixture.createFixtureF(helper, "ae10-rebuild", withFlaggedPurchase = true)
        val before = fixture.capture(file)

        // Copy-drop-rename rebuild of manual_recurring_expenses with the same rows (ids and order kept).
        fixture.mutate(
            file,
            "CREATE TABLE manual_recurring_expenses_x (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "merchant TEXT NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL DEFAULT 'EUR', " +
                "frequency TEXT NOT NULL, nextDate INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                "isActive INTEGER NOT NULL DEFAULT 1)",
            "INSERT INTO manual_recurring_expenses_x (id, merchant, amount, currency, frequency, nextDate, " +
                "createdAt, isActive) SELECT id, merchant, amount, currency, frequency, nextDate, createdAt, " +
                "isActive FROM manual_recurring_expenses ORDER BY id DESC",
            "DROP TABLE manual_recurring_expenses",
            "ALTER TABLE manual_recurring_expenses_x RENAME TO manual_recurring_expenses"
        )
        val after = fixture.capture(file)

        assertEquals(before, after)
        assertTrue(
            SemanticAggregates.compare(before, after, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
                is SemanticCheckResult.Verified
        )
    }

    @Test
    fun AE10_migrations_before_152_leave_the_fingerprinted_columns_unchanged() {
        // 145..151 predate the spending-exclusion column, so the aggregates themselves cannot be captured
        // there (see the VERSION tests below). The fingerprinted columns that exist at v145 are compared
        // directly before and after the pre-152 migrations: any undeclared write or rebuild shows up.
        val preBaseline = DatabaseMigrations.ALL.filter { it.endVersion <= 151 }.toTypedArray()
        assertTrue("pre-152 migrations are registered", preBaseline.isNotEmpty())
        for (start in DatabaseSchemaPolicy.MIGRATION_BASELINE until 151) {
            val name = "ae10-legacy-$start"
            val file = fixture.createFixtureF(helper, name, version = start)
            val before = legacyDigest(file)
            assertEquals("fixture rows are present at v$start", 6 + 2, before.size)

            helper.runMigrationsAndValidate(name, 151, true, *preBaseline).close()
            val after = legacyDigest(file)

            assertEquals("start $start: pre-152 migrations changed a fingerprinted column", before, after)
        }
    }

    private fun legacyDigest(file: File): List<String> = fixture.readOnly(file) { db ->
        val rows = mutableListOf<String>()
        db.rawQuery(
            "SELECT id, transactionType, currency, amount, isNotMine, isSharedExpense, myShareAmount, " +
                "mySharePercentage FROM expenses ORDER BY id",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += "E|" + (0 until cursor.columnCount).joinToString("|") {
                    if (cursor.isNull(it)) "null" else cursor.getString(it)
                }
            }
        }
        db.rawQuery("SELECT id, isActive, currency, amount FROM manual_recurring_expenses ORDER BY id", null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows += "R|" + (0 until cursor.columnCount).joinToString("|") {
                        if (cursor.isNull(it)) "null" else cursor.getString(it)
                    }
                }
            }
        rows
    }

    // ── Part 2: pre-152 sections are NOT_VERIFIABLE(VERSION) ─────

    @Test
    fun AE10_synthetic_v149_section_is_not_verifiable_version_and_never_verified() {
        val file = fixture.createFixtureF(helper, "ae10-v149-synthetic")
        val section = fixture.capture(file)
        // The section as the exporter of an old app would have written it, after a JSON round trip.
        val parsed = SemanticAggregates.parse(section.toJson())
        assertTrue(parsed is SemanticParseResult.Parsed)

        val direct = BackupVerifier.expectedAggregatesAfterMigration(section, 149)
        val viaSection = BackupVerifier.expectedAggregatesForSection(parsed, 149)

        listOf(direct, viaSection).forEach { basis ->
            assertTrue("expected NotVerifiable but was $basis", basis is SemanticBasis.NotVerifiable)
            assertEquals(SemanticNotVerifiableReason.VERSION, (basis as SemanticBasis.NotVerifiable).reason)
        }
        assertEquals(
            SemanticCode.SEMANTIC_NOT_VERIFIABLE_VERSION,
            SemanticCode.forNotVerifiable(SemanticNotVerifiableReason.VERSION)
        )
        // The restore proceeds under the existing gates and the outcome is never VERIFIED.
        val summary = BackupVerifier.verify(file, fixture.manifestCounts(file), compareExpectation(direct, SemanticCheckpoint.C3_LIVE_POST_SWAP))
        assertTrue(summary.errors.toString(), summary.passed)
        val result = summary.semanticResult
        assertTrue("expected NotVerifiable but was ${result?.javaClass?.simpleName}", result is SemanticCheckResult.NotVerifiable)
        assertEquals(SemanticNotVerifiableReason.VERSION, (result as SemanticCheckResult.NotVerifiable).reason)
        assertFalse(summary.semanticResult is SemanticCheckResult.Verified)
    }

    @Test
    fun AE10_a_real_v149_database_migrated_to_current_is_not_verifiable_version_even_though_its_data_matches() {
        // A v149 database cannot be fingerprinted by this definition set: the spending column is absent, so
        // capture degrades instead of failing.
        val oldFile = fixture.createFixtureF(helper, "ae10-v149-real", version = 149)
        val oldCapture = fixture.capture(oldFile)
        assertEquals(SemanticCaptureStatus.NOT_CAPTURED, oldCapture.status)
        assertEquals(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED, oldCapture.notCapturedReason)

        // Migrate it through the registered chain; a section carrying the same business rows is attached
        // to the old source version (synthetic: an app at v149 never wrote one).
        helper.runMigrationsAndValidate("ae10-v149-real", current, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS).close()
        val reference = fixture.capture(fixture.createFixtureF(helper, "ae10-v149-reference"))
        val migrated = fixture.capture(oldFile)
        assertEquals("migration preserved the business rows", reference, migrated)

        val basis = BackupVerifier.expectedAggregatesAfterMigration(reference, 149)
        assertTrue(basis is SemanticBasis.NotVerifiable)
        assertEquals(SemanticNotVerifiableReason.VERSION, (basis as SemanticBasis.NotVerifiable).reason)
        val summary = BackupVerifier.verify(
            oldFile, fixture.manifestCounts(oldFile), compareExpectation(basis, SemanticCheckpoint.C2_STAGED_POST_MIGRATION)
        )
        assertTrue(summary.errors.toString(), summary.passed)
        val result = summary.semanticResult as SemanticCheckResult.NotVerifiable
        assertEquals(SemanticNotVerifiableReason.VERSION, result.reason)
    }

    @Test
    fun AE10_source_version_gate_boundaries() {
        val captured = fixture.capture(fixture.createFixtureF(helper, "ae10-gate"))

        // 151 is below the gate, 152 is the first comparable version.
        val at151 = BackupVerifier.expectedAggregatesAfterMigration(captured, 151)
        assertEquals(SemanticNotVerifiableReason.VERSION, (at151 as SemanticBasis.NotVerifiable).reason)
        val at152 = BackupVerifier.expectedAggregatesAfterMigration(captured, 152)
        assertTrue("expected Compare but was $at152", at152 is SemanticBasis.Compare)
        // An unknown source version cannot be trusted either.
        val unknownSource = BackupVerifier.expectedAggregatesAfterMigration(captured, null)
        assertEquals(SemanticNotVerifiableReason.VERSION, (unknownSource as SemanticBasis.NotVerifiable).reason)
        // An unknown section version is never interpreted, even at a comparable source version.
        val unknownSection = SemanticAggregateSet(99, SemanticCaptureStatus.NOT_CAPTURED, null, emptyMap())
        val unknown = BackupVerifier.expectedAggregatesAfterMigration(unknownSection, 152)
        assertEquals(SemanticNotVerifiableReason.VERSION, (unknown as SemanticBasis.NotVerifiable).reason)
        // Other reasons are unaffected by the version gate at 152.
        val notCaptured = BackupVerifier.expectedAggregatesAfterMigration(
            SemanticAggregateSet.ofNotCaptured(SemanticNotCapturedReason.SEMANTIC_CAPTURE_FAILED), 152
        )
        assertEquals(SemanticNotVerifiableReason.NOT_CAPTURED, (notCaptured as SemanticBasis.NotVerifiable).reason)
        val legacy = BackupVerifier.expectedAggregatesAfterMigration(null, 152)
        assertEquals(SemanticNotVerifiableReason.LEGACY, (legacy as SemanticBasis.NotVerifiable).reason)
    }
}
