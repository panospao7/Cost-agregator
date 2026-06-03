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
 * 3. Create a MIGRATION_145_146 (or N-1 → N) object
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

    /** All registered migrations, starting from v145 baseline. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_145_146)
}
