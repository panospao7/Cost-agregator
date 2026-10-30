package com.yourname.expensetracker.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yourname.expensetracker.data.database.entity.SavingsSweepMonthAcceptance
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider

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
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `negotiation_outcomes` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`subscriptionId` INTEGER NOT NULL, " +
                    "`outcome` TEXT NOT NULL, " +
                    "`oldAmount` REAL NOT NULL, " +
                    "`newAmount` REAL, " +
                    "`currency` TEXT NOT NULL, " +
                    "`savingsAmount` REAL, " +
                    "`notes` TEXT, " +
                    "`marketRateSource` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`subscriptionId`) REFERENCES `manual_recurring_expenses`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_subscriptionId ON negotiation_outcomes(subscriptionId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_createdAt ON negotiation_outcomes(createdAt)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_negotiation_outcomes_outcome ON negotiation_outcomes(outcome)")
        }
    }

    val MIGRATION_146_147 = object : Migration(146, 147) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Feature B: leftAt tracking for soft-deleted members
            database.execSQL("ALTER TABLE group_members ADD COLUMN `leftAt` INTEGER")
            // Feature A: idempotency key for group expense deduplication
            database.execSQL("ALTER TABLE group_expenses ADD COLUMN `idempotencyKey` TEXT")
            // Drop the unique index on (groupId, name) — soft-deleted members must not
            // block re-admission of members with the same name.
            database.execSQL("DROP INDEX IF EXISTS index_group_members_groupId_name")
            // Recreate as a non-unique index to preserve query performance
            database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members(groupId, name)")
        }
    }

    /** PR12A: Add worker-run tracing columns to background_job_runs. */
    val MIGRATION_147_148 = object : Migration(147, 148) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `workId` TEXT")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `uniqueWorkName` TEXT")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `specVersion` INTEGER")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `runAttempt` INTEGER")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `leaseId` TEXT")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `terminalReasonCode` TEXT")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `terminalDiagnosticCode` TEXT")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `partialFailureCount` INTEGER")
            database.execSQL("ALTER TABLE background_job_runs ADD COLUMN `failedTargetCount` INTEGER")
        }
    }

    /**
     * RP-16 16-B (D9): durable worker-run counter columns on background_job_runs.
     * Additive only; pre-existing rows measure as 0 skipped / 0 errors (they never
     * recorded those counters).
     *
     * RP-17 17-D (register D12): stable privacy-safe bank-review identity on
     * pending_reviews. Adds bankReviewIdentity (unique index — atomic
     * insert-if-absent for bank reviews under concurrent syncs) and
     * bankConnectionScopeHash (indexed — disconnect deletes only the reviews
     * scoped to one connection identity). Both nullable; existing (non-bank)
     * rows keep NULL, which never conflicts on a unique index.
     *
     * MERGE NOTE: RP-16 and RP-17 ran as parallel lanes, each defining a
     * 148→149 migration over disjoint tables; combined here into one body.
     */
    val MIGRATION_148_149 = object : Migration(148, 149) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE background_job_runs ADD COLUMN `rowsSkipped` INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "ALTER TABLE background_job_runs ADD COLUMN `errors` INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL("ALTER TABLE pending_reviews ADD COLUMN `bankReviewIdentity` TEXT")
            database.execSQL("ALTER TABLE pending_reviews ADD COLUMN `bankConnectionScopeHash` TEXT")
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_pending_reviews_bankReviewIdentity` ON `pending_reviews` (`bankReviewIdentity`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_pending_reviews_bankConnectionScopeHash` ON `pending_reviews` (`bankConnectionScopeHash`)"
            )
        }
    }

    /** B8/R16: durable, idempotent savings sweep acceptance ledger. */
    val MIGRATION_149_150 = object : Migration(149, 150) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `savings_sweep_plan` ADD COLUMN `acceptanceKey` TEXT")
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_savings_sweep_plan_acceptanceKey` " +
                    "ON `savings_sweep_plan` (`acceptanceKey`)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `savings_contribution_ledger` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`idempotencyKey` TEXT NOT NULL, " +
                    "`goalId` INTEGER NOT NULL, " +
                    "`amount` REAL NOT NULL, " +
                    "`currency` TEXT NOT NULL DEFAULT 'EUR', " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`sweepPlanId` INTEGER, " +
                    "FOREIGN KEY(`goalId`) REFERENCES `savings_goals`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_savings_contribution_ledger_idempotencyKey` " +
                    "ON `savings_contribution_ledger` (`idempotencyKey`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_savings_contribution_ledger_goalId` " +
                    "ON `savings_contribution_ledger` (`goalId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_savings_contribution_ledger_timestamp` " +
                    "ON `savings_contribution_ledger` (`timestamp`)"
            )
        }
    }

    /** F2: goal-independent monthly acceptance survives goal cascade deletion. */
    val MIGRATION_150_151 = SavingsSweepMonthMigration(
        com.yourname.expensetracker.domain.util.SystemTimeProvider()
    )

    /** Keeps schema SQL in the canonical migration owner, with a testable clock. */
    internal fun MIGRATION_150_151(database: SupportSQLiteDatabase, timeProvider: TimeProvider) {
        val now = timeProvider.now()
        check(now > 0L) { "SAVINGS_SWEEP_TIME_UNAVAILABLE" }
        val start = TimePeriodUtils.getStartOfMonth(now)
        val end = TimePeriodUtils.getEndOfMonth(now)
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `savings_sweep_month_acceptance` (" +
                "`monthStart` INTEGER NOT NULL, `monthEndExclusive` INTEGER NOT NULL, " +
                "`actionedAt` INTEGER NOT NULL, `reasonCode` TEXT NOT NULL, " +
                "PRIMARY KEY(`monthStart`))"
        )
        // v150 cascade deletion may already have erased all acceptance history.
        // Absence is not proof of an unused month. Seed exactly one marker, even
        // for an empty legacy DB; fresh v151 installs do not run this migration.
        database.execSQL(
            "INSERT INTO `savings_sweep_month_acceptance` " +
                "(`monthStart`, `monthEndExclusive`, `actionedAt`, `reasonCode`) VALUES (?, ?, ?, ?)",
            arrayOf(start, end, now, SavingsSweepMonthAcceptance.LEGACY_HISTORY_UNKNOWN)
        )
    }

    /**
     * Loan installments (S2): purely additive.
     *
     * - `installment_plans` / `installment_entries` are new and empty after migration.
     *   No foreign key to `expenses` (purchaseExpenseId / linkedExpenseId are plain
     *   unique nullable columns; SQLite allows many NULLs under a unique index).
     * - `expenses.spendingExclusionReason` is a nullable TEXT column with DEFAULT NULL,
     *   so every existing row reads NULL and counts exactly as before.
     * - Enums are stored as TEXT, money as REAL (2 dp), civil dates as INTEGER epochDay.
     * - No index on `spendingExclusionReason`: it is mostly NULL and low-selectivity, so an
     *   index gains nothing for reads and would cost write amplification on every expense
     *   insert; adding one later would need another schema bump. Decision recorded here.
     * - DDL mirrors the Room entities (InstallmentPlan / InstallmentEntry / Expense) so
     *   the Room schema validator passes: same column set, NOT NULL, defaults and
     *   `index_<table>_<cols>` index names.
     */
    val MIGRATION_151_152 = object : Migration(151, 152) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `installment_plans` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`planUuid` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`purchaseExpenseId` INTEGER, " +
                    "`displayName` TEXT NOT NULL, " +
                    "`lenderLabel` TEXT, " +
                    "`currency` TEXT NOT NULL, " +
                    "`splitMethod` TEXT NOT NULL, " +
                    "`declaredTotal` REAL, " +
                    "`fixedPaymentAmount` REAL, " +
                    "`installmentCount` INTEGER NOT NULL, " +
                    "`frequency` TEXT NOT NULL, " +
                    "`firstDueDate` INTEGER NOT NULL, " +
                    "`anchorDayOfMonth` INTEGER NOT NULL, " +
                    "`residualPlacement` TEXT NOT NULL, " +
                    "`categoryId` INTEGER, " +
                    "`paymentMethod` TEXT NOT NULL, " +
                    "`fulfilmentMode` TEXT NOT NULL, " +
                    "`declaredOnDate` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`statusReason` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`terminalAt` INTEGER)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_plans_planUuid` " +
                    "ON `installment_plans` (`planUuid`)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_plans_purchaseExpenseId` " +
                    "ON `installment_plans` (`purchaseExpenseId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_plans_status` " +
                    "ON `installment_plans` (`status`)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS `installment_entries` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`planId` INTEGER NOT NULL, " +
                    "`seq` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`dueDate` INTEGER NOT NULL, " +
                    "`scheduledAmount` REAL NOT NULL, " +
                    "`originalScheduledAmount` REAL, " +
                    "`status` TEXT NOT NULL, " +
                    "`statusReason` TEXT, " +
                    "`linkedExpenseId` INTEGER, " +
                    "`linkOrigin` TEXT, " +
                    "`autoRecordConsumed` INTEGER NOT NULL, " +
                    "`recordGeneration` INTEGER NOT NULL, " +
                    "`adjustReason` TEXT, " +
                    "`statusChangedAt` INTEGER NOT NULL, " +
                    "`adjustedAt` INTEGER, " +
                    "FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_entries_planId_kind_seq` " +
                    "ON `installment_entries` (`planId`, `kind`, `seq`)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_entries_linkedExpenseId` " +
                    "ON `installment_entries` (`linkedExpenseId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_entries_dueDate` " +
                    "ON `installment_entries` (`dueDate`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_installment_entries_status` " +
                    "ON `installment_entries` (`status`)"
            )
            database.execSQL("ALTER TABLE `expenses` ADD COLUMN `spendingExclusionReason` TEXT DEFAULT NULL")
        }
    }

    /** All registered migrations, starting from v145 baseline. */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_145_146,
        MIGRATION_146_147,
        MIGRATION_147_148,
        MIGRATION_148_149,
        MIGRATION_149_150,
        MIGRATION_150_151,
        MIGRATION_151_152
    )
}
