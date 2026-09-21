package com.yourname.expensetracker.data.database

import androidx.room.testing.MigrationTestHelper
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
 * RP-16 16-B (D9): the 148→149 migration must additively add the durable
 * `rowsSkipped` / `errors` counter columns to `background_job_runs` with a
 * 0 default (measured-zero for rows that never recorded them), preserve all
 * pre-existing rows, and be registered in the canonical migration chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Migration148to149Test {

    private val TEST_DB_NAME = "migration-148-149-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 148 to 149 adds rowsSkipped and errors columns with zero defaults`() {
        val db = helper.createDatabase(TEST_DB_NAME, 148)

        // Seed a pre-migration worker run row.
        db.execSQL(
            "INSERT INTO background_job_runs (id, workerName, startedAt, status, rowsScanned, rowsUpdated, notificationsSent) " +
                "VALUES (1, 'data_retention', 1700000000000, 'SUCCESS', 10, 5, 2)"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 149, true, DatabaseMigrations.MIGRATION_148_149)

        // Pre-existing row survives with the new counters defaulted to measured zero.
        migrated.query("SELECT workerName, rowsScanned, rowsUpdated, notificationsSent, rowsSkipped, errors FROM background_job_runs WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("data_retention", cursor.getString(0))
            assertEquals(10, cursor.getInt(1))
            assertEquals(5, cursor.getInt(2))
            assertEquals(2, cursor.getInt(3))
            assertEquals("pre-existing rows must default to measured zero", 0, cursor.getInt(4))
            assertEquals("pre-existing rows must default to measured zero", 0, cursor.getInt(5))
        }

        // The new columns are NOT NULL with 0 defaults: a row that supplies the
        // pre-existing NOT NULL columns but omits rowsSkipped/errors can still
        // be inserted (rowsScanned/rowsUpdated/notificationsSent have no SQL
        // default in either v148 or v149, so they must be explicit here).
        migrated.execSQL(
            "INSERT INTO background_job_runs (id, workerName, startedAt, status, rowsScanned, rowsUpdated, notificationsSent) " +
                "VALUES (2, 'bill_reminder_periodic', 1700000001000, 'RUNNING', 0, 0, 0)"
        )
        migrated.query("SELECT rowsSkipped, errors FROM background_job_runs WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(0, cursor.getInt(1))
        }

        migrated.close()
    }

    @Test
    fun `ALL_MIGRATIONS includes migration 148 to 149`() {
        val migrations = DatabaseSchemaPolicy.ALL_MIGRATIONS
        assertTrue(
            "Migration 148→149 must be registered in ALL_MIGRATIONS",
            migrations.any { it.startVersion == 148 && it.endVersion == 149 }
        )
    }

    @Test
    fun `schema version is 149`() {
        assertEquals(149, DatabaseSchemaPolicy.CURRENT_VERSION)
    }
}
