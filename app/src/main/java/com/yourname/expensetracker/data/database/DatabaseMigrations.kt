package com.yourname.expensetracker.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registry of supported Room migrations.
 *
 * v145 is the baseline. There are intentionally no historical migrations
 * below v145 — old databases must use the financial rescue/import path.
 *
 * When adding a new schema version:
 * 1. Bump APP_DATABASE_SCHEMA_VERSION
 * 2. Export the new schema JSON
 * 3. Create a MIGRATION_N-1_N (e.g. MIGRATION_146_147) object
 * 4. Add it to [ALL]
 * 5. Test with MigrationTestHelper
 */
object DatabaseMigrations {

    val MIGRATION_145_146 = object : Migration(145, 146) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS negotiation_outcomes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    subscriptionId INTEGER NOT NULL,
                    outcome TEXT NOT NULL,
                    oldAmount REAL NOT NULL,
                    newAmount REAL,
                    currency TEXT NOT NULL,
                    savingsAmount REAL,
                    notes TEXT,
                    marketRateSource TEXT,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY(subscriptionId) REFERENCES manual_recurring_expenses(id) ON DELETE CASCADE
                )
            """)
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_subscriptionId ON negotiation_outcomes(subscriptionId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_createdAt ON negotiation_outcomes(createdAt)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_outcome ON negotiation_outcomes(outcome)")
        }
    }

    val MIGRATION_146_147 = object : Migration(146, 147) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Feature B: leftAt tracking for soft-deleted members
            database.execSQL("ALTER TABLE group_members ADD COLUMN leftAt INTEGER")
            // Feature A: idempotency key for group expense deduplication
            database.execSQL("ALTER TABLE group_expenses ADD COLUMN idempotencyKey TEXT")
            // Drop the unique index on (groupId, name) — soft-deleted members must not
            // block re-admission of members with the same name.
            database.execSQL("DROP INDEX IF EXISTS index_group_members_groupId_name")
            // Recreate as a non-unique index to preserve query performance
            database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members(groupId, name)")
        }
    }

    /** All registered migrations, starting from v145 baseline. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_145_146, MIGRATION_146_147)
}
