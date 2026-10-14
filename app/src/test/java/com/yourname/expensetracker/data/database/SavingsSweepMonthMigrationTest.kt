package com.yourname.expensetracker.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SavingsSweepMonthMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(migrationTestInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val now = 1_710_000_000_000L

    @Test
    fun v150_upgrade_preserves_goal_ledger_and_plan_and_consumes_upgrade_month() {
        val name = "sweep-month-preservation"
        val old = helper.createDatabase(name, 150)
        old.execSQL("INSERT INTO savings_goals (id, name, targetAmount, currentAmount, protectionLevel, currency, currencyAssumption, createdAt) VALUES (1, 'Savings', 1000.0, 240.0, 'WARNING', 'EUR', 'EXPLICIT', 1710000000000)")
        old.execSQL("INSERT INTO savings_sweep_plan (id, goalId, monthEnd, totalUnderspend, riskBuffer, safeSweepAmount, allocatedAmount, allocationPercentage, status, confidence, currency, computedAt, acceptanceKey) VALUES (1, 1, 1710000001000, 50.0, 10.0, 40.0, 40.0, 1.0, 'ACCEPTED', 0.9, 'EUR', 1710000000000, 'accepted-key')")
        old.execSQL("INSERT INTO savings_contribution_ledger (idempotencyKey, goalId, amount, currency, timestamp, source, sweepPlanId) VALUES ('accepted-key', 1, 40.0, 'EUR', 1710000000000, 'sweep', 1)")
        old.close()
        val upgraded = helper.runMigrationsAndValidate(name, 151, true, SavingsSweepMonthMigration(FakeTimeProvider(now)))
        try {
            assertMarker(upgraded)
            upgraded.query("SELECT currentAmount FROM savings_goals WHERE id = 1").use { row ->
                assertTrue(row.moveToFirst()); assertEquals(240.0, row.getDouble(0), 0.0)
            }
            upgraded.query("SELECT status, acceptanceKey FROM savings_sweep_plan WHERE id = 1").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("ACCEPTED", row.getString(0)); assertEquals("accepted-key", row.getString(1))
            }
            upgraded.query("SELECT amount, currency FROM savings_contribution_ledger").use { row ->
                assertTrue(row.moveToFirst()); assertEquals(40.0, row.getDouble(0), 0.0); assertEquals("EUR", row.getString(1))
            }
            upgraded.execSQL("PRAGMA foreign_keys = ON")
            upgraded.execSQL("DELETE FROM savings_goals WHERE id = 1")
            assertMarker(upgraded)
            upgraded.query("PRAGMA foreign_key_list(savings_sweep_month_acceptance)").use { assertFalse(it.moveToFirst()) }
        } finally { upgraded.close() }
    }

    @Test
    fun absent_v150_history_is_unknown_not_proof_of_unused_month() {
        val name = "sweep-month-unknown"
        helper.createDatabase(name, 150).close()
        helper.runMigrationsAndValidate(name, 151, true, SavingsSweepMonthMigration(FakeTimeProvider(now))).use { upgraded ->
            assertMarker(upgraded)
            val start = TimePeriodUtils.getStartOfMonth(now)
            val end = TimePeriodUtils.getEndOfMonth(now)
            upgraded.query("SELECT COUNT(*) FROM savings_sweep_month_acceptance WHERE monthStart < $end AND monthEndExclusive > $start").use { row ->
                assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0))
            }
            val nextEnd = TimePeriodUtils.getEndOfMonth(end)
            upgraded.query("SELECT COUNT(*) FROM savings_sweep_month_acceptance WHERE monthStart < $nextEnd AND monthEndExclusive > $end").use { row ->
                assertTrue(row.moveToFirst()); assertEquals(0, row.getInt(0))
            }
        }
    }

    @Test
    fun clock_is_read_when_migration_runs_not_when_it_is_registered() {
        val name = "sweep-month-deferred-clock"
        val clock = FakeTimeProvider(0L)
        val migration = SavingsSweepMonthMigration(clock)
        helper.createDatabase(name, 150).close()
        clock.setTime(now)
        helper.runMigrationsAndValidate(name, 151, true, migration).use { upgraded ->
            assertMarker(upgraded)
        }
    }

    @Test
    fun unavailable_clock_fails_before_any_schema_or_marker_write() {
        helper.createDatabase("sweep-month-unavailable-clock", 150).use { database ->
            try {
                SavingsSweepMonthMigration(FakeTimeProvider(0L)).migrate(database)
                fail("Unavailable clock must reject migration")
            } catch (error: IllegalStateException) {
                assertEquals("SAVINGS_SWEEP_TIME_UNAVAILABLE", error.message)
            }
            database.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'savings_sweep_month_acceptance'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(0, row.getInt(0))
            }
            database.query("PRAGMA user_version").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals(150, row.getInt(0))
            }
        }
    }

    private fun assertMarker(database: SupportSQLiteDatabase) {
        database.query("SELECT monthStart, monthEndExclusive, actionedAt, reasonCode FROM savings_sweep_month_acceptance").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(TimePeriodUtils.getStartOfMonth(now), row.getLong(0))
            assertEquals(TimePeriodUtils.getEndOfMonth(now), row.getLong(1))
            assertEquals(now, row.getLong(2))
            assertEquals(SavingsSweepMonthAcceptance.LEGACY_HISTORY_UNKNOWN, row.getString(3))
            assertFalse(row.moveToNext())
        }
    }
}
