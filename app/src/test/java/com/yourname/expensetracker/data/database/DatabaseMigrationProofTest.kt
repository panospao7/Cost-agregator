package com.yourname.expensetracker.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hardened JVM/Robolectric migration-proof test.
 *
 * Validates that the migration chain from [DatabaseSchemaPolicy.MIGRATION_BASELINE]
 * to [DatabaseSchemaPolicy.CURRENT_VERSION] works end-to-end, that the production
 * [DatabaseMigrations.ALL] array is correctly registered, and that a fresh-install
 * schema is identical to a migrated schema.
 *
 * This test runs as part of `testDebugUnitTest` — no emulator required.
 * Missing schemas are failures (no [assumeTrue], no [org.junit.Ignore]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseMigrationProofTest {

    private companion object {
        const val TEST_DB = "migration-proof-test"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    // ── Chain validation ──────────────────────────────────────────────

    /**
     * Create a database at the migration baseline, seed a row, migrate through
     * ALL registered migrations to the current version, and verify the seed
     * data survived.
     */
    @Test
    fun migration_145_to_148_chain_passes() {
        var db = helper.createDatabase(TEST_DB, DatabaseSchemaPolicy.MIGRATION_BASELINE)

        // Seed a minimal expense row at the baseline schema
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) " +
                "VALUES (1, 100.0, 'EUR', 'ProofTest', 1000, 'PURCHASE', 0)"
        )
        db.close()

        // Migrate through every registered step and validate against exported schemas
        db = helper.runMigrationsAndValidate(
            TEST_DB,
            DatabaseSchemaPolicy.CURRENT_VERSION,
            true,
            *DatabaseSchemaPolicy.ALL_MIGRATIONS
        )

        // Seed data must survive the full migration chain
        val cursor = db.query("SELECT COUNT(*) FROM expenses")
        assertTrue(cursor.moveToFirst())
        assertEquals("Seed row must survive migration chain", 1, cursor.getInt(0))
        cursor.close()

        // Verify the seed row's values are preserved
        val row = db.query(
            "SELECT id, amount, currency, merchant, date, transactionType FROM expenses WHERE id = 1"
        )
        assertTrue(row.moveToFirst())
        assertEquals(1L, row.getLong(0))
        assertEquals(100.0, row.getDouble(1), 0.0)
        assertEquals("EUR", row.getString(2))
        assertEquals("ProofTest", row.getString(3))
        assertEquals(1000L, row.getLong(4))
        assertEquals("PURCHASE", row.getString(5))
        row.close()

        db.close()
    }

    // ── Production registration audit ─────────────────────────────────

    /**
     * Verifies that [DatabaseMigrations.ALL] (exposed through
     * [DatabaseSchemaPolicy.ALL_MIGRATIONS]) forms a continuous,
     * gapless chain from [DatabaseSchemaPolicy.MIGRATION_BASELINE]
     * to [DatabaseSchemaPolicy.CURRENT_VERSION].
     *
     * Any gap here means the production builder is missing a migration
     * step and the app will crash on upgrade.
     */
    @Test
    fun production_ALL_MIGRATIONS_forms_continuous_chain() {
        val registered = DatabaseSchemaPolicy.ALL_MIGRATIONS
        assertTrue("ALL_MIGRATIONS must not be empty", registered.isNotEmpty())

        val sorted = registered.sortedBy { it.startVersion }

        // First migration must start at the baseline
        assertEquals(
            "First migration must start at MIGRATION_BASELINE",
            DatabaseSchemaPolicy.MIGRATION_BASELINE,
            sorted.first().startVersion
        )

        // Chain must be continuous — each step's end equals the next step's start
        for (i in 0 until sorted.size - 1) {
            val current = sorted[i]
            val next = sorted[i + 1]
            assertEquals(
                "Migration chain gap: v${current.startVersion}→v${current.endVersion} " +
                    "does not connect to v${next.startVersion}→v${next.endVersion}",
                current.endVersion,
                next.startVersion
            )
        }

        // Last migration must land on CURRENT_VERSION
        assertEquals(
            "Last migration must end at CURRENT_VERSION",
            DatabaseSchemaPolicy.CURRENT_VERSION,
            sorted.last().endVersion
        )
    }

    // ── Fresh-vs-migrated schema parity ───────────────────────────────

    /**
     * A fresh-install database at [DatabaseSchemaPolicy.CURRENT_VERSION]
     * must have the same schema as a database created at the baseline
     * and migrated through all registered steps.
     *
     * This proves there is no schema drift between the two paths.
     */
    @Test
    fun fresh_install_schema_matches_migrated_schema() {
        // Fresh DB at current version
        val freshDb = helper.createDatabase(
            "proof-fresh",
            DatabaseSchemaPolicy.CURRENT_VERSION
        )
        val freshSchema = dumpNormalizedSchema(freshDb)
        freshDb.close()

        // Migrated DB from baseline
        var migratedDb = helper.createDatabase(
            "proof-migrated",
            DatabaseSchemaPolicy.MIGRATION_BASELINE
        )
        migratedDb.close()
        migratedDb = helper.runMigrationsAndValidate(
            "proof-migrated",
            DatabaseSchemaPolicy.CURRENT_VERSION,
            true,
            *DatabaseSchemaPolicy.ALL_MIGRATIONS
        )
        val migratedSchema = dumpNormalizedSchema(migratedDb)
        migratedDb.close()

        assertEquals(
            "Fresh-install and migrated database schemas must be identical",
            freshSchema,
            migratedSchema
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Collects the DDL of every user table, normalised for comparison.
     * Excludes SQLite internal tables and Room metadata tables.
     */
    private fun dumpNormalizedSchema(db: SupportSQLiteDatabase): String {
        val statements = mutableListOf<String>()

        db.query(
            "SELECT name, sql FROM sqlite_master " +
                "WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'room_%' " +
                "AND name NOT LIKE 'android_%' " +
                "AND sql IS NOT NULL " +
                "ORDER BY name"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                val sql = cursor.getString(1) ?: continue
                statements.add(
                    sql
                        .replace(Regex("\\s+"), " ")
                        .replace("\${TABLE_NAME}", name)
                        .trim()
                )
            }
        }

        return statements.sorted().joinToString("\n")
    }
}
