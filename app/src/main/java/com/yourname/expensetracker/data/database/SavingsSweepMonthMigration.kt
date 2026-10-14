package com.yourname.expensetracker.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yourname.expensetracker.domain.util.TimeProvider

/** Preserves legacy data, conservatively consuming the upgrade/restore month. */
class SavingsSweepMonthMigration(private val timeProvider: TimeProvider) : Migration(150, 151) {
    override fun migrate(database: SupportSQLiteDatabase) {
        DatabaseMigrations.MIGRATION_150_151(database, timeProvider)
    }
}
