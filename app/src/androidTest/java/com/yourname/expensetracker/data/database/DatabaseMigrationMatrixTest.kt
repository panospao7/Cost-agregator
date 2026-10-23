package com.yourname.expensetracker.data.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationMatrixTest {

    private val testDb = "migration-matrix-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private fun hasSchema(version: Int): Boolean {
        val path = "${AppDatabase::class.java.canonicalName}/$version.json"
        return try {
            InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { }
            true
        } catch (_: IOException) {
            false
        }
    }

    // LOWEST exported schema JSON is v33 — the oldest version we have a snapshot for.
    private val LOWEST_EXPORTED_SCHEMA = 33

    // Versions below the supported baseline are not implicitly reset.
    private val MIGRATION_BASELINE = DatabaseSchemaPolicy.MIGRATION_BASELINE

    // Current latest DB version.
    private val CURRENT_VERSION = DatabaseSchemaPolicy.CURRENT_VERSION

    // ── Baseline Chain Tests ──────────────────────────────────────────

    /**
     * Tests the ACTIVE migration chain from the baseline to the current version.
     * These are the only migrations registered in DatabaseMigrations.ALL.
     * This is the critical path — if this fails, the migration ladder is broken.
     */
    @Test
    @Throws(IOException::class)
    fun baseline_chain_to_current_version_passes() {
        assertTrue(hasSchema(MIGRATION_BASELINE) && hasSchema(CURRENT_VERSION))

        var db = helper.createDatabase("matrix-145-150", MIGRATION_BASELINE)
        seedExpense(db)
        db.close()

        db = helper.runMigrationsAndValidate(
            "matrix-145-150", CURRENT_VERSION, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS
        )
        assertSeedExpense(db)
        db.close()
    }

    /**
     * Individual migration: v145 → v146 (negotiation_outcomes table).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_145_to_146_adds_negotiation_outcomes() {
        assertTrue(hasSchema(145) && hasSchema(146))

        var db = helper.createDatabase("matrix-145-146", 145)
        seedExpense(db)
        db.close()

        db = helper.runMigrationsAndValidate("matrix-145-146", 146, true, DatabaseMigrations.MIGRATION_145_146)

        // Verify table exists
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='negotiation_outcomes'")
        assertTrue(cursor.moveToFirst())
        cursor.close()

        // Verify indices exist
        assertTrue(hasIndex(db, "index_negotiation_outcomes_subscriptionId"))
        assertTrue(hasIndex(db, "index_negotiation_outcomes_createdAt"))
        assertTrue(hasIndex(db, "index_negotiation_outcomes_outcome"))
        assertSeedExpense(db)

        db.close()
    }

    /**
     * Individual migration: v146 → v147 (group member leftAt, idempotencyKey).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_146_to_147_adds_leftAt_and_idempotencyKey() {
        assertTrue(hasSchema(146) && hasSchema(147))

        var db = helper.createDatabase("matrix-146-147", 146)
        db.close()

        db = helper.runMigrationsAndValidate("matrix-146-147", 147, true, DatabaseMigrations.MIGRATION_146_147)

        // Verify leftAt column on group_members
        val colCursor = db.query("PRAGMA table_info(group_members)")
        var hasLeftAt = false
        while (colCursor.moveToNext()) {
            if (colCursor.getString(colCursor.getColumnIndex("name")) == "leftAt") hasLeftAt = true
        }
        colCursor.close()
        assertTrue("leftAt column missing from group_members", hasLeftAt)

        // Verify idempotencyKey column on group_expenses
        val colCursor2 = db.query("PRAGMA table_info(group_expenses)")
        var hasIdempotencyKey = false
        while (colCursor2.moveToNext()) {
            if (colCursor2.getString(colCursor2.getColumnIndex("name")) == "idempotencyKey") hasIdempotencyKey = true
        }
        colCursor2.close()
        assertTrue("idempotencyKey column missing from group_expenses", hasIdempotencyKey)

        db.close()
    }

    /**
     * Individual migration: v147 → v148 (worker-run tracing columns).
     */
    @Test
    @Throws(IOException::class)
    fun migrate_147_to_148_adds_worker_tracing_columns() {
        assertTrue(hasSchema(147) && hasSchema(148))

        var db = helper.createDatabase("matrix-147-148", 147)
        db.close()

        db = helper.runMigrationsAndValidate("matrix-147-148", 148, true, DatabaseMigrations.MIGRATION_147_148)

        val colCursor = db.query("PRAGMA table_info(background_job_runs)")
        val columns = mutableListOf<String>()
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndex("name")))
        }
        colCursor.close()

        assertTrue("workId column missing", columns.contains("workId"))
        assertTrue("uniqueWorkName column missing", columns.contains("uniqueWorkName"))
        assertTrue("specVersion column missing", columns.contains("specVersion"))
        assertTrue("runAttempt column missing", columns.contains("runAttempt"))
        assertTrue("leaseId column missing", columns.contains("leaseId"))
        assertTrue("terminalReasonCode column missing", columns.contains("terminalReasonCode"))
        assertTrue("terminalDiagnosticCode column missing", columns.contains("terminalDiagnosticCode"))
        assertTrue("partialFailureCount column missing", columns.contains("partialFailureCount"))
        assertTrue("failedTargetCount column missing", columns.contains("failedTargetCount"))

        db.close()
    }

    /**
     * Individual migration: v148 → v149 (RP-17 17-D: bank review identity
     * columns + unique index). The supported schema snapshots are required.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_148_to_149_adds_bank_review_identity() {
        assertTrue(hasSchema(148) && hasSchema(149))

        var db = helper.createDatabase("matrix-148-149", 148)
        db.close()

        db = helper.runMigrationsAndValidate("matrix-148-149", 149, true, DatabaseMigrations.MIGRATION_148_149)

        val colCursor = db.query("PRAGMA table_info(pending_reviews)")
        val columns = mutableListOf<String>()
        while (colCursor.moveToNext()) {
            columns.add(colCursor.getString(colCursor.getColumnIndex("name")))
        }
        colCursor.close()
        assertTrue("bankReviewIdentity column missing", columns.contains("bankReviewIdentity"))
        assertTrue("bankConnectionScopeHash column missing", columns.contains("bankConnectionScopeHash"))

        assertTrue(hasIndex(db, "index_pending_reviews_bankReviewIdentity"))
        assertTrue(hasIndex(db, "index_pending_reviews_bankConnectionScopeHash"))

        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate_149_to_150_adds_savings_acceptance_ledger() {
        assertTrue(hasSchema(149) && hasSchema(150))
        var db = helper.createDatabase("matrix-149-150", 149)
        db.execSQL(
            "INSERT INTO savings_goals " +
                "(id, name, targetAmount, currentAmount, protectionLevel, currency, currencyAssumption, createdAt) " +
                "VALUES (1, 'Savings', 1000.0, 240.0, 'WARNING', 'EUR', 'EXPLICIT', 1710000000000)"
        )
        db.execSQL(
            "INSERT INTO savings_sweep_plan " +
                "(id, goalId, monthEnd, totalUnderspend, riskBuffer, safeSweepAmount, allocatedAmount, " +
                "allocationPercentage, status, confidence, currency, computedAt) " +
                "VALUES (1, 1, 1710000001000, 50.0, 10.0, 40.0, 40.0, 1.0, 'ACCEPTED', 0.9, 'EUR', 1710000000000)"
        )
        db.close()
        db = helper.runMigrationsAndValidate("matrix-149-150", 150, true, DatabaseMigrations.MIGRATION_149_150)
        assertTrue(hasIndex(db, "index_savings_sweep_plan_acceptanceKey"))
        assertTrue(hasIndex(db, "index_savings_contribution_ledger_idempotencyKey"))
        db.query("SELECT currentAmount, currencyAssumption FROM savings_goals WHERE id = 1").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(240.0, row.getDouble(0), 0.0)
            assertEquals("EXPLICIT", row.getString(1))
        }
        db.query("SELECT status, acceptanceKey FROM savings_sweep_plan WHERE id = 1").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals("ACCEPTED", row.getString(0))
            assertTrue(row.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM savings_contribution_ledger").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(0, row.getInt(0))
        }
        db.close()
    }

    // ── Fresh-vs-Migrated Schema Parity ───────────────────────────────

    /**
     * Fresh creation and supported upgrades must produce the same tables.
     * runMigrationsAndValidate separately checks the migrated schema against
     * the current exported Room snapshot, including columns, indexes and FKs.
     */
    @Test
    @Throws(IOException::class)
    fun fresh_vs_migrated_table_parity_at_current_version() {
        assertTrue(hasSchema(MIGRATION_BASELINE) && hasSchema(CURRENT_VERSION))

        // Create a fresh database at the current version.
        val freshDb = AppDatabase.fileBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, "matrix-fresh-current"
        ).allowMainThreadQueries().build()
        val freshTables = try {
            getTableNames(freshDb.openHelper.writableDatabase)
        } finally {
            freshDb.close()
        }

        // Create at the baseline and migrate to current.
        var migratedDb = helper.createDatabase("matrix-migrated-150", MIGRATION_BASELINE)
        seedExpense(migratedDb)
        migratedDb.close()
        migratedDb = helper.runMigrationsAndValidate(
            "matrix-migrated-150", CURRENT_VERSION, true, *DatabaseSchemaPolicy.ALL_MIGRATIONS
        )
        assertSeedExpense(migratedDb)
        val migratedTables = getTableNames(migratedDb)
        migratedDb.close()

        // Both paths should produce the same set of tables
        assertEquals(
            "Fresh and migrated databases must have identical tables",
            freshTables.sorted(), migratedTables.sorted()
        )
    }

    @Test
    fun migrate_150_to_151_adds_goal_independent_month_marker() {
        val name = "matrix-150-151"
        val referenceTime = 1710000000000L
        val before = helper.createDatabase(name, 150)
        before.close()
        val upgraded = helper.runMigrationsAndValidate(name, 151, true,
            SavingsSweepMonthMigration(object : com.yourname.expensetracker.domain.util.TimeProvider {
                override fun now() = referenceTime
            }))
        try {
            upgraded.query("SELECT monthStart, monthEndExclusive, reasonCode FROM savings_sweep_month_acceptance").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(referenceTime), row.getLong(0))
                assertEquals(com.yourname.expensetracker.domain.util.TimePeriodUtils.getEndOfMonth(referenceTime), row.getLong(1))
                assertEquals("SAVINGS_SWEEP_LEGACY_MONTH_UNKNOWN", row.getString(2))
                assertFalse(row.moveToNext())
            }
            upgraded.query("PRAGMA foreign_key_list(savings_sweep_month_acceptance)").use { assertFalse(it.moveToFirst()) }
        } finally { upgraded.close() }
    }

    /**
     * Individual migration: v151 -> v152 (loan installments S2: installment_plans,
     * installment_entries, expenses.spendingExclusionReason). The 152.json snapshot is
     * generated by the Room compiler; runMigrationsAndValidate checks against it.
     */
    @Test
    @Throws(IOException::class)
    fun migrate_151_to_152_adds_installment_tables_and_exclusion_column() {
        assertTrue(hasSchema(151) && hasSchema(152))
        val name = "matrix-151-152"
        val before = helper.createDatabase(name, 151)
        seedExpense(before)
        before.close()
        val upgraded = helper.runMigrationsAndValidate(name, 152, true, DatabaseMigrations.MIGRATION_151_152)
        try {
            assertSeedExpense(upgraded)
            upgraded.query("SELECT spendingExclusionReason FROM expenses WHERE id = 1").use { row ->
                assertTrue(row.moveToFirst())
                assertTrue("Pre-existing expense must read NULL spendingExclusionReason", row.isNull(0))
            }
            assertEquals(listOf("installment_entries", "installment_plans"),
                getTableNames(upgraded).filter { it.startsWith("installment_") }.sorted())
            for (table in listOf("installment_plans", "installment_entries")) {
                upgraded.query("SELECT COUNT(*) FROM $table").use { row ->
                    assertTrue(row.moveToFirst())
                    assertEquals("$table must be empty after migration", 0, row.getInt(0))
                }
            }
            assertTrue(hasIndex(upgraded, "index_installment_plans_planUuid"))
            assertTrue(hasIndex(upgraded, "index_installment_plans_purchaseExpenseId"))
            assertTrue(hasIndex(upgraded, "index_installment_entries_planId_kind_seq"))
            assertTrue(hasIndex(upgraded, "index_installment_entries_linkedExpenseId"))
            upgraded.query("PRAGMA foreign_key_list(installment_entries)").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("installment_plans", row.getString(row.getColumnIndexOrThrow("table")))
                assertEquals("planId", row.getString(row.getColumnIndexOrThrow("from")))
                assertEquals("CASCADE", row.getString(row.getColumnIndexOrThrow("on_delete")))
                assertFalse(row.moveToNext())
            }
            upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { upgraded.close() }
    }

    // Unsupported databases must fail closed, not be silently reset.

    /**
     * The canonical builder has no destructive fallback. Refusing an unsupported
     * database must preserve its schema version and data for explicit rescue.
     */
    @Test
    @Throws(IOException::class)
    fun pre_baseline_database_is_refused_without_destroying_data() {
        assertTrue(hasSchema(LOWEST_EXPORTED_SCHEMA))
        assertTrue(LOWEST_EXPORTED_SCHEMA in DatabaseSchemaPolicy.UNSUPPORTED_VERSIONS)
        val name = "matrix-pre-baseline-preservation"
        val db = helper.createDatabase(name, LOWEST_EXPORTED_SCHEMA)
        db.execSQL(
            "INSERT INTO expenses (id, amount, currency, merchant, transactionType, date, createdAt, " +
                "paymentMethod, isManualEntry, isNotMine, isSharedExpense, backfillAttempts) " +
                "VALUES (1, 999.0, 'EUR', 'Synthetic legacy row', 'PURCHASE', 1000, 1000, 'UNKNOWN', 0, 0, 0, 0)"
        )
        db.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val currentDb = AppDatabase.fileBuilder(context, name).allowMainThreadQueries().build()
        try {
            try {
                currentDb.openHelper.writableDatabase
                fail("Unsupported versions must not be migrated or silently reset")
            } catch (_: IllegalStateException) {
                // Expected: there is no supported migration path from this version.
            }
        } finally {
            currentDb.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            legacy.rawQuery("PRAGMA user_version", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(LOWEST_EXPORTED_SCHEMA, it.getInt(0))
            }
            legacy.rawQuery("SELECT id, amount, currency FROM expenses", null).use {
                assertTrue("Legacy row must survive failed upgrade", it.moveToFirst())
                assertEquals(1L, it.getLong(0))
                assertEquals(999.0, it.getDouble(1), 0.0)
                assertEquals("EUR", it.getString(2))
                assertFalse(it.moveToNext())
            }
        }
    }

    @Test
    fun supported_schema_assets_are_bom_free_utf8_json() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (version in MIGRATION_BASELINE..CURRENT_VERSION) {
            val bytes = assets.open("${AppDatabase::class.java.canonicalName}/$version.json").use { it.readBytes() }
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            assertTrue("Supported schema must start with a JSON object", bytes.isNotEmpty() && bytes[0] == '{'.code.toByte())
            assertFalse("Supported schema must not contain UTF-16 NUL bytes", bytes.any { it == 0.toByte() })
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun seedExpense(db: SupportSQLiteDatabase) {
        // effectiveAmount is computed in Kotlin, not a persisted Room column.
        db.execSQL("INSERT INTO expenses (id, amount, currency, merchant, date, transactionType, isNotMine) VALUES (1, 100.0, 'EUR', 'Synthetic expense', 1000, 'PURCHASE', 0)")
    }

    private fun assertSeedExpense(db: SupportSQLiteDatabase) {
        db.query("SELECT amount, currency FROM expenses WHERE id = 1").use {
            assertTrue("Migration must preserve the expense", it.moveToFirst())
            assertEquals(100.0, it.getDouble(0), 0.0)
            assertEquals("EUR", it.getString(1))
        }
    }

    private fun hasIndex(db: SupportSQLiteDatabase, indexName: String): Boolean {
        db.query("SELECT name FROM sqlite_master WHERE type='index' AND name='$indexName'").use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun getTableNames(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name NOT LIKE 'android_%'"
        ).use { cursor ->
            while (cursor.moveToNext()) tables.add(cursor.getString(0))
        }
        return tables
    }
}
