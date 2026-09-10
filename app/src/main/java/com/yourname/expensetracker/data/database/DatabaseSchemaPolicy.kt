package com.yourname.expensetracker.data.database

/**
 * Single source of truth for database migration configuration.
 * Production code AND tests must reference this, not hardcoded values.
 *
 * ## Purpose
 * Centralises the migration baseline, current version, unsupported range,
 * and the registered migration array so that every consumer — builders,
 * workers, tests, and CI scripts — reads the same authoritative values.
 */
object DatabaseSchemaPolicy {
    /** The current latest database schema version. */
    const val CURRENT_VERSION = APP_DATABASE_SCHEMA_VERSION

    /** The minimum version from which supported migration is guaranteed. */
    const val MIGRATION_BASELINE = 145

    /** Versions below BASELINE use destructive migration. */
    val UNSUPPORTED_VERSIONS: IntRange get() = 1..<MIGRATION_BASELINE

    /** All registered migrations (delegates to production builder). */
    val ALL_MIGRATIONS: Array<androidx.room.migration.Migration>
        get() = DatabaseMigrations.ALL
}
