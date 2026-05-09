package com.yourname.expensetracker.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class MigrationRegistrationTest {

    private val TEST_DB_NAME = "migration-registration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 120 to 121 creates group_lifecycle_events table`() {
        val db = helper.createDatabase(TEST_DB_NAME, 120)
        db.execSQL("INSERT INTO expenses (id, amount, currency, merchant, date, effectiveAmount, transactionType, isNotMine) VALUES (1, 100.0, 'EUR', 'Test', 1000, 100.0, 'PURCHASE', 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 121, true, AppDatabase.MIGRATION_120_121)
        val cursor = migrated.query("SELECT * FROM group_lifecycle_events")
        assertThat(cursor.count).isEqualTo(0) // table exists but is empty
        cursor.close()
        migrated.close()
    }

    @Test
    fun `migration 119 to 121 creates both settlement and lifecycle tables`() {
        val db = helper.createDatabase(TEST_DB_NAME, 119)
        db.close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB_NAME, 121, true, AppDatabase.MIGRATION_119_120, AppDatabase.MIGRATION_120_121)
        val cursor = migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='group_settlements'")
        assertThat(cursor.count).isEqualTo(1)
        cursor.close()
        val cursor2 = migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='group_lifecycle_events'")
        assertThat(cursor2.count).isEqualTo(1)
        cursor2.close()
        migrated.close()
    }

    @Test
    fun `ALL_MIGRATIONS includes migration 117 to 119`() {
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertTrue(
            "Migration 117→118 must be registered in ALL_MIGRATIONS",
            migrations.any { it.startVersion == 117 && it.endVersion == 118 }
        )
        assertTrue(
            "Migration 118→119 must be registered in ALL_MIGRATIONS",
            migrations.any { it.startVersion == 118 && it.endVersion == 119 }
        )
    }
}
