package com.yourname.expensetracker.data.database

import androidx.room.migration.Migration

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

    /** All registered migrations, starting from v145 baseline. */
    val ALL: Array<Migration> = emptyArray()
}
