package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.dao.*
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentRecommendation
import com.yourname.expensetracker.data.database.entity.BudgetAdjustmentEvent
import com.yourname.expensetracker.data.database.entity.SpendingPersonalityProfileEntity
import com.yourname.expensetracker.data.database.entity.StressForecastSnapshot
import com.yourname.expensetracker.data.database.entity.EmailReceiptSource
import com.yourname.expensetracker.data.security.BankTokenCipher

/**
 * ## RSP-R2A: Migration path for v1–v5 (pre-migration-chain)
 * Users upgrading from a database created by the very first app releases
 * (schema versions 1 through 5) will encounter a gap: the current migration
 * chain starts at version 6 (the lowest schema for which we export Room JSON
 * schemas and provide a [Migration] object).
 *
 * ### What happens on v1–v5 databases
 * Room's [fallbackToDestructiveMigration] will DROP all tables and recreate
 * them from scratch, losing all user data. This is the **only** automated
 * path for pre-v6 databases because:
 * - No Room `@Database(version = N)` annotation ever existed for v1–v5 in the
 *   current codebase — those schemas were from the very first prototype builds.
 * - We do not export or test JSON schema files for versions 1–5 (they predate
 *   `exportSchema = true`).
 * - Creating individual [Migration] objects for 1→6, 2→6, etc. would require
 *   reverse-engineering the table shapes from ancient git history.
 *
 * ### Recommendation
 * Users on v1–v5 should be directed to export their data before upgrading,
 * then perform a fresh install. Alternatively, a [LegacyDatabaseImporter]
 * (see RSP-R5A) can be used to import data from a legacy `.db` file into
 * the current schema after a destructive migration.
 *
 * The test [DatabaseMigrationTest.fallback_to_destructive_migration_works]
 * specifically validates that a v5 database is correctly handled by
 * [fallbackToDestructiveMigration].
 */
const val APP_DATABASE_SCHEMA_VERSION = 145

@Database(
    entities = [
        RawNotification::class,
        BlockedPackage::class,
        Expense::class,
        Category::class,
        MerchantCategory::class,
        PendingReview::class,
        UserCorrection::class,
        SourceStats::class,
        Budget::class,
        ScannedReceipt::class,
        ManualRecurringExpense::class,
        PlannedExpense::class,
        SavingsGoal::class,
        MerchantCanonical::class,
        MerchantAlias::class,
        MerchantLocation::class,
        MerchantLocationCorrection::class,
        AiArtifactEntity::class,
        AiChatSessionEntity::class,
        AiChatMessageEntity::class,
        RecommendationEntity::class,
        ReceiptItemCategorization::class,
        Warranty::class,
        ReturnWindow::class,
        SubscriptionPriceHistory::class,
        SubscriptionUsage::class,
        MileageTracking::class,
        ExchangeRate::class,
        ExpenseGroup::class,
        GroupMember::class,
        GroupExpense::class,
        BudgetForecast::class,
        Investment::class,
        InvestmentValue::class,
        BankConnection::class,
        SplitTemplate::class,
        SplitItemAssignment::class,
        AnomalyAlert::class,
        PromptState::class,
        HealthScoreHistory::class,
        SavingsSweepPlan::class,
        SubscriptionCandidate::class,
        BudgetAdjustmentRecommendation::class,
        BudgetAdjustmentEvent::class,
        SpendingPersonalityProfileEntity::class,
        StressForecastSnapshot::class,
        EmailReceiptSource::class,
        SpendingChallengeEntity::class,
        TransactionEvent::class,
        ReceiptEvent::class,
        ReceiptExpenseLink::class,
        RecurringOccurrence::class,
        RecurringReminderDelivery::class,
        RecurringLifecycleEvent::class,
        PrivacyAuditEvent::class,
        BackgroundJobRun::class,
        SourceStatsEvent::class,
        WarrantyLifecycleEvent::class,
        WarrantyReminderDelivery::class,
        InvestmentTransaction::class,
        GroupSettlementEntity::class,
        GroupLifecycleEventEntity::class,
        PipelineDiagnosticEvent::class,
        OperationRun::class,
        OperationRunEvent::class,
        EntitySourceLink::class,
        NotificationIntakeEntity::class,
        BankStatementImportRun::class,
        BankStatementImportItem::class
    ],
    version = APP_DATABASE_SCHEMA_VERSION,
    exportSchema = true
)
@TypeConverters(com.yourname.expensetracker.data.database.converter.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    // ... (DAOs)
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun blockedPackageDao(): BlockedPackageDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun sourceStatsDao(): SourceStatsDao
    abstract fun budgetDao(): BudgetDao
    abstract fun scannedReceiptDao(): ScannedReceiptDao
    abstract fun recurringExpenseDao(): RecurringExpenseDao
    abstract fun manualRecurringExpenseDao(): ManualRecurringExpenseDao
    abstract fun plannedExpenseDao(): PlannedExpenseDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun merchantNormalizationDao(): MerchantNormalizationDao
    abstract fun merchantLocationDao(): MerchantLocationDao
    abstract fun aiArtifactDao(): AiArtifactDao
    abstract fun aiChatSessionDao(): AiChatSessionDao
    abstract fun aiChatMessageDao(): AiChatMessageDao
    abstract fun recommendationDao(): RecommendationDao
    abstract fun receiptItemCategorizationDao(): ReceiptItemCategorizationDao
    abstract fun warrantyDao(): WarrantyDao
    abstract fun returnWindowDao(): ReturnWindowDao
    abstract fun subscriptionPriceHistoryDao(): SubscriptionPriceHistoryDao
    abstract fun subscriptionUsageDao(): SubscriptionUsageDao
    abstract fun mileageTrackingDao(): MileageTrackingDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun expenseGroupDao(): ExpenseGroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun groupExpenseDao(): GroupExpenseDao
    abstract fun budgetForecastDao(): BudgetForecastDao
    abstract fun investmentDao(): InvestmentDao
    abstract fun investmentValueDao(): InvestmentValueDao
    abstract fun bankConnectionDao(): BankConnectionDao
    abstract fun splitTemplateDao(): SplitTemplateDao
    abstract fun splitItemAssignmentDao(): SplitItemAssignmentDao
    abstract fun anomalyAlertDao(): AnomalyAlertDao
    abstract fun promptStateDao(): PromptStateDao
    abstract fun healthScoreHistoryDao(): HealthScoreHistoryDao
    abstract fun savingsSweepPlanDao(): SavingsSweepPlanDao
    abstract fun subscriptionCandidateDao(): SubscriptionCandidateDao
    abstract fun budgetAdjustmentDao(): BudgetAdjustmentDao
    abstract fun stressForecastSnapshotDao(): StressForecastSnapshotDao
    abstract fun spendingPersonalityProfileDao(): SpendingPersonalityProfileDao
    abstract fun emailReceiptDao(): EmailReceiptDao
    abstract fun spendingChallengeDao(): SpendingChallengeDao
    abstract fun transactionEventDao(): TransactionEventDao
    abstract fun receiptEventDao(): ReceiptEventDao
    abstract fun receiptExpenseLinkDao(): ReceiptExpenseLinkDao
    abstract fun recurringOccurrenceDao(): RecurringOccurrenceDao
    abstract fun recurringReminderDeliveryDao(): RecurringReminderDeliveryDao
    abstract fun recurringLifecycleEventDao(): RecurringLifecycleEventDao
    abstract fun privacyAuditDao(): PrivacyAuditDao
    abstract fun backgroundJobRunDao(): BackgroundJobRunDao
    abstract fun pipelineDiagnosticEventDao(): PipelineDiagnosticEventDao
    abstract fun sourceStatsEventDao(): SourceStatsEventDao
    abstract fun warrantyLifecycleEventDao(): WarrantyLifecycleEventDao
    abstract fun warrantyReminderDeliveryDao(): WarrantyReminderDeliveryDao
    abstract fun investmentTransactionDao(): InvestmentTransactionDao
    abstract fun groupSettlementDao(): GroupSettlementDao
    abstract fun groupLifecycleEventDao(): GroupLifecycleEventDao
    abstract fun operationRunDao(): OperationRunDao
    abstract fun operationRunEventDao(): OperationRunEventDao
    abstract fun entitySourceLinkDao(): EntitySourceLinkDao
    abstract fun notificationIntakeDao(): NotificationIntakeDao
    abstract fun bankStatementImportRunDao(): BankStatementImportRunDao
    abstract fun bankStatementImportItemDao(): BankStatementImportItemDao

    companion object {
        const val DATABASE_NAME = "expense_tracker_db"

        /**
         * ## E6: INSERT INTO ... SELECT * fragility
         *
         * Several migrations below use `INSERT INTO ... SELECT *` (without
         * explicit column lists) when rebuilding tables.  This is fragile
         * because:
         *   - If a future migration reorders columns in the NEW table, the
         *     SELECT * silently maps wrong values.
         *   - If columns are added/removed, SELECT * will either fail (wrong
         *     count) or produce silent data corruption.
         *
         * Affected locations (11 occurrences as of v108):
         *   - MIGRATION_49_50  (expenses_new, scanned_receipts_new)
         *   - MIGRATION_68_69  (expenses_new)
         *   - ~~FRESH_INSTALL_CALLBACK (savings_goals_new, mileage_tracking_new,
         *     pending_reviews_new, group_members_new, planned_expenses_new)~~ FIXED v112
         *   - MIGRATION_106_107 (budgets_new, group_members_new,
         *     planned_expenses_new)
         *   - MIGRATION_107_108 (planned_expenses_new)
         *
         * **Recommendation**: All future table rebuilds MUST use explicit
         * column lists in both CREATE TABLE and INSERT statements so that
         * the migration is resilient to column reordering.
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN isManualEntry INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN notes TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER,
                        amount REAL NOT NULL,
                        period TEXT NOT NULL,
                        startDate INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                        notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                        rollover INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastWarningNotifiedAt INTEGER,
                        lastCriticalNotifiedAt INTEGER,
                        lastExceededNotifiedAt INTEGER,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scanned_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imagePath TEXT NOT NULL,
                        rawOcrText TEXT NOT NULL,
                        parsedTotal REAL,
                        parsedMerchant TEXT,
                        parsedDate INTEGER,
                        parsedItems TEXT,
                        parsedTaxAmount REAL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        confidence REAL NOT NULL,
                        expenseId INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // To change NOT NULL constraint in SQLite, we must recreate the table
                database.beginTransaction()
                try {
                    database.execSQL("ALTER TABLE pending_reviews RENAME TO pending_reviews_old")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS pending_reviews (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            rawNotificationId INTEGER,
                            scannedReceiptId INTEGER,
                            suggestedAmount REAL NOT NULL,
                            suggestedCurrency TEXT NOT NULL,
                            suggestedMerchant TEXT NOT NULL,
                            suggestedType TEXT NOT NULL,
                            suggestedCategoryId INTEGER,
                            confidence REAL NOT NULL,
                            packageName TEXT NOT NULL,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            FOREIGN KEY(rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                            FOREIGN KEY(scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("""
                        INSERT INTO pending_reviews (
                            id, rawNotificationId, suggestedAmount, suggestedCurrency, 
                            suggestedMerchant, suggestedType, suggestedCategoryId, 
                            confidence, packageName, notificationTitle, notificationText, 
                            createdAt, status
                        )
                        SELECT 
                            id, rawNotificationId, suggestedAmount, suggestedCurrency, 
                            suggestedMerchant, suggestedType, suggestedCategoryId, 
                            confidence, packageName, notificationTitle, notificationText, 
                            createdAt, status
                        FROM pending_reviews_old
                    """.trimIndent())
                    
                    database.execSQL("DROP TABLE pending_reviews_old")
                    
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedDate INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS manual_recurring_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        merchant TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        frequency TEXT NOT NULL,
                        nextDate INTEGER NOT NULL,
                        note TEXT,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS planned_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        description TEXT NOT NULL,
                        amount REAL NOT NULL,
                        date INTEGER NOT NULL,
                        categoryId INTEGER,
                        isRecurring INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS savings_goals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL,
                        currentAmount REAL NOT NULL DEFAULT 0.0,
                        targetDate INTEGER,
                        protectionLevel TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_stats (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        totalNotifications INTEGER NOT NULL DEFAULT 0,
                        acceptedAsExpense INTEGER NOT NULL DEFAULT 0,
                        rejectedByUser INTEGER NOT NULL DEFAULT 0,
                        autoRejected INTEGER NOT NULL DEFAULT 0,
                        pendingReview INTEGER NOT NULL DEFAULT 0,
                        lastSeen INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // RawNotifications index
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_isRelevant` ON `raw_notifications` (`isRelevant`)")
                
                // Expense indices optimization
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_date`")
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_categoryId`")
                database.execSQL("DROP INDEX IF EXISTS `index_expenses_transactionType`")
                
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_transactionType_merchant` ON `expenses` (`transactionType`, `merchant`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_expenses_transactionType_categoryId_date` ON `expenses` (`transactionType`, `categoryId`, `date`)")
            }
        }

        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. UserCorrections: Recreate table to add Foreign Keys and Indices, and new columns
                database.beginTransaction()
                try {
                    // Create new table with updated schema (FKs + Indices support)
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS user_corrections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            packageName TEXT NOT NULL,
                            originalMerchant TEXT NOT NULL,
                            correctedMerchant TEXT,
                            originalAmount REAL NOT NULL,
                            correctedAmount REAL,
                            originalCategoryId INTEGER,
                            correctedCategoryId INTEGER,
                            wasRejected INTEGER NOT NULL DEFAULT 0,
                            wasApproved INTEGER NOT NULL DEFAULT 0,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY(originalCategoryId) REFERENCES categories(id) ON DELETE SET NULL,
                            FOREIGN KEY(correctedCategoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())

                    // Copy data from old table
                    // We assume originalCategoryId and correctedCategoryId are new columns, so we don't migrate them.
                    database.execSQL("""
                        INSERT INTO user_corrections_new (
                            id, packageName, originalMerchant, correctedMerchant, 
                            originalAmount, correctedAmount, wasRejected, wasApproved, 
                            notificationTitle, notificationText, createdAt
                        )
                        SELECT 
                            id, packageName, originalMerchant, correctedMerchant, 
                            originalAmount, correctedAmount, wasRejected, wasApproved, 
                            notificationTitle, notificationText, createdAt
                        FROM user_corrections
                    """.trimIndent())

                    // Swap tables
                    database.execSQL("DROP TABLE user_corrections")
                    database.execSQL("ALTER TABLE user_corrections_new RENAME TO user_corrections")

                    // Create indices for UserCorrection
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_originalCategoryId ON user_corrections (originalCategoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_correctedCategoryId ON user_corrections (correctedCategoryId)")

                    // 2. Expenses: Update Indices to match Expense.kt
                    // Drop obsolete indices
                    database.execSQL("DROP INDEX IF EXISTS index_expenses_transactionType_merchant")
                    database.execSQL("DROP INDEX IF EXISTS index_expenses_date_transactionType") // Fixes schema mismatch crash
                    
                    // Create new/updated indices
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_rawNotificationId ON expenses (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_date ON expenses (transactionType, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_categoryId_date ON expenses (categoryId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_amount_merchant_date ON expenses (amount, merchant, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchant_date ON expenses (merchant, date)")
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create canonical merchants table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_canonicals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedName TEXT NOT NULL,
                        searchKey TEXT NOT NULL,
                        categoryId INTEGER,
                        totalOccurrences INTEGER NOT NULL DEFAULT 0,
                        totalSpent REAL NOT NULL DEFAULT 0.0,
                        isVerified INTEGER NOT NULL DEFAULT 0,
                        logoUrl TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """)
                
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_canonicals_normalizedName ON merchant_canonicals (normalizedName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_searchKey ON merchant_canonicals (searchKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_categoryId ON merchant_canonicals (categoryId)")

                // Create merchant aliases table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_aliases (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawName TEXT NOT NULL,
                        normalizedKey TEXT NOT NULL,
                        canonicalId INTEGER NOT NULL,
                        occurrenceCount INTEGER NOT NULL DEFAULT 1,
                        isUserDefined INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        FOREIGN KEY(canonicalId) REFERENCES merchant_canonicals(id) ON DELETE CASCADE
                    )
                """)
                
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_aliases_rawName ON merchant_aliases (rawName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_normalizedKey ON merchant_aliases (normalizedKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_canonicalId ON merchant_aliases (canonicalId)")

                // Migrate existing merchants
                val now = System.currentTimeMillis()
                database.execSQL("""
                    INSERT INTO merchant_canonicals (normalizedName, searchKey, categoryId, totalOccurrences, totalSpent, isVerified, createdAt, updatedAt)
                    SELECT 
                        merchant as normalizedName,
                        LOWER(REPLACE(REPLACE(REPLACE(merchant, '.', ''), '''', ''), ' ', '')) as searchKey,
                        categoryId,
                        COUNT(*) as totalOccurrences,
                        SUM(amount) as totalSpent,
                        0 as isVerified,
                        $now as createdAt,
                        $now as updatedAt
                    FROM expenses
                    WHERE merchant IS NOT NULL AND transactionType IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER')
                    GROUP BY merchant
                """)

                database.execSQL("""
                    INSERT INTO merchant_aliases (rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt)
                    SELECT 
                        mc.normalizedName as rawName,
                        mc.searchKey as normalizedKey,
                        mc.id as canonicalId,
                        mc.totalOccurrences as occurrenceCount,
                        0 as isUserDefined,
                        mc.createdAt,
                        mc.updatedAt as lastUsedAt
                    FROM merchant_canonicals mc
                """)
            }
        }

        val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // INS-008: Standalone date index for efficient range queries and ordering
                database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses (date)")
            }
        }

        val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add duplicates column to source_stats
                database.execSQL("ALTER TABLE source_stats ADD COLUMN duplicates INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Restore index lost in MIGRATION_15_16
                database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_merchant_date ON expenses (transactionType, merchant, date)")
            }
        }

        val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add dedupeKey column for atomic duplicate prevention
                database.execSQL("ALTER TABLE expenses ADD COLUMN dedupeKey TEXT DEFAULT NULL")
                
                // Generate dedupe keys for existing data
                database.execSQL("""
                    UPDATE expenses SET dedupeKey = 
                        printf('%.2f', amount) || '_' || 
                        LOWER(REPLACE(REPLACE(REPLACE(merchant, ' ', ''), CHAR(9), ''), CHAR(10), '')) || '_' ||
                        (date / 300000)
                """)
                
                // Set dedupeKey to NULL for any duplicates (keep one copy)
                database.execSQL("""
                    UPDATE expenses SET dedupeKey = NULL WHERE id NOT IN (
                        SELECT MIN(id) FROM expenses 
                        GROUP BY dedupeKey 
                        HAVING dedupeKey IS NOT NULL
                    )
                """)
                
                // Create unique index
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_dedupeKey ON expenses(dedupeKey)")
            }
        }

        val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add unique index for raw_notifications to prevent duplicates
                database.execSQL("""
                    DELETE FROM raw_notifications WHERE id NOT IN (
                        SELECT MIN(id) FROM raw_notifications 
                        GROUP BY packageName, timestamp, title, text
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text ON raw_notifications(packageName, timestamp, title, text)")
            }
        }

        val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add indices for user_corrections for faster lookups
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_packageName ON user_corrections(packageName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_wasApproved ON user_corrections(wasApproved)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_wasRejected ON user_corrections(wasRejected)")
            }
        }

        val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Transfer and shared expense tracking
                database.execSQL("ALTER TABLE expenses ADD COLUMN transferDirection TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN transferAccountName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN isNotMine INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE expenses ADD COLUMN ownerName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN isSharedExpense INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE expenses ADD COLUMN sharedWithName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN mySharePercentage INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN myShareAmount REAL DEFAULT NULL")
            }
        }

        // Migration 24 -> 25: Add transfer direction to pending_reviews
        val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedDirection TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedAccountName TEXT DEFAULT NULL")
            }
        }

        // Migration 25 -> 26: Add normalizedCanonicalName to merchant_categories
        val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE merchant_categories ADD COLUMN normalizedCanonicalName TEXT DEFAULT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_categories_normalizedCanonicalName ON merchant_categories (normalizedCanonicalName)")
            }
        }

        // Migration 26 -> 27: Add matchType and explanation to pending_reviews
        val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN matchType TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN explanation TEXT DEFAULT NULL")
            }
        }

        // Migration 27 -> 28: Geolocation & Maps feature
        // - Add latitude/longitude/locationSource/placeId to expenses
        // - Add suggestedLatitude/suggestedLongitude to pending_reviews
        // - Create merchant_locations cache table
        // - Create merchant_location_corrections table
        val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add location columns to expenses
                database.execSQL("ALTER TABLE expenses ADD COLUMN latitude REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN longitude REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN locationSource TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN placeId TEXT DEFAULT NULL")
                // Index for location-based queries (bug #22 fix)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_latitude_longitude ON expenses (latitude, longitude)")

                // 2. Add suggested location to pending_reviews
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedLatitude REAL DEFAULT NULL")
                database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedLongitude REAL DEFAULT NULL")

                // 3. Create merchant_locations cache table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_locations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedMerchantName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        source TEXT NOT NULL,
                        osmId TEXT DEFAULT NULL,
                        displayAddress TEXT DEFAULT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        lastResolvedAt INTEGER NOT NULL,
                        hitCount INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName ON merchant_locations (normalizedMerchantName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt ON merchant_locations (lastResolvedAt)")

                // 4. Create merchant_location_corrections table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS merchant_location_corrections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        normalizedMerchantName TEXT NOT NULL,
                        correctedLatitude REAL NOT NULL,
                        correctedLongitude REAL NOT NULL,
                        areaLatitude REAL DEFAULT NULL,
                        areaLongitude REAL DEFAULT NULL,
                        areaKey TEXT NOT NULL DEFAULT '',
                        areaRadiusKm REAL NOT NULL DEFAULT 5.0,
                        osmId TEXT DEFAULT NULL,
                        displayAddress TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                // Composite unique index so INSERT OR REPLACE deduplicates by merchant+area
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_location_corrections_merchant_area ON merchant_location_corrections (normalizedMerchantName, areaKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_location_corrections_createdAt ON merchant_location_corrections (createdAt)")
            }
        }

        // Migration 28 -> 29: Add backfillAttempts to expenses to prevent infinite geocode retries
        val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE expenses ADD COLUMN backfillAttempts INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration 29 -> 30: Add resolvedAddress to expenses; add areaKey to merchant_locations
        // (merchant_locations must be recreated to change the unique index to composite)
        val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add resolvedAddress to expenses
                database.execSQL("ALTER TABLE expenses ADD COLUMN resolvedAddress TEXT DEFAULT NULL")

                // 2. Recreate merchant_locations to change unique index from single-column
                //    (normalizedMerchantName) to composite (normalizedMerchantName, areaKey)
                database.beginTransaction()
                try {
                    database.execSQL("ALTER TABLE merchant_locations RENAME TO merchant_locations_old")
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS merchant_locations (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedMerchantName TEXT NOT NULL,
                            areaKey TEXT,
                            displayName TEXT NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            source TEXT NOT NULL,
                            osmId TEXT,
                            displayAddress TEXT,
                            confidence REAL NOT NULL,
                            lastResolvedAt INTEGER NOT NULL,
                            hitCount INTEGER NOT NULL
                        )
                    """.trimIndent())
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName_areaKey " +
                        "ON merchant_locations (normalizedMerchantName, areaKey)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt " +
                        "ON merchant_locations (lastResolvedAt)"
                    )
                    // Copy existing rows — provide default values for legacy data
                    database.execSQL("""
                        INSERT INTO merchant_locations
                            (id, normalizedMerchantName, areaKey, displayName, latitude, longitude,
                             source, osmId, displayAddress, confidence, lastResolvedAt, hitCount)
                        SELECT id, normalizedMerchantName, NULL, displayName, latitude, longitude,
                               source, osmId, displayAddress, 
                               COALESCE(confidence, 1.0), lastResolvedAt, 
                               COALESCE(hitCount, 1)
                        FROM merchant_locations_old
                    """.trimIndent())
                    database.execSQL("DROP TABLE merchant_locations_old")
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add missing index on lastResolvedAt for cleanup queries
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt " +
                    "ON merchant_locations (lastResolvedAt)"
                )
            }
        }

        // Migration 31 -> 32: Add merchantKey column to expenses.
        // merchantKey is the unified canonical key produced by MerchantKeyGenerator:
        //   Greek → Latin (diphthong-aware) → lowercase → strip [^a-z0-9].
        // The column is added as NULL so the migration is instant (no table rebuild).
        // MerchantKeyBackfillWorker runs asynchronously on first launch after upgrade
        // to populate the column for all existing rows using the real Kotlin logic
        // (SQLite cannot replicate the diphthong-aware transliteration accurately).
        val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN merchantKey TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_expenses_merchantKey " +
                    "ON expenses (merchantKey)"
                )
            }
        }

        // Migration 32 -> 33: Re-key merchant location tables.
        // MerchantLocationRepository.normalizeKey() previously used a Greek-preserving
        // charset ([\p{L}\p{N}], take 30) so rows for Greek-named merchants were keyed
        // as e.g. "σκλαβενίτης". The unified MerchantKeyGenerator now produces Latin keys
        // ("sklavenitis") so all existing rows become unreachable.
        //
        // merchant_locations is a pure cache — wiping it causes a one-time re-geocode
        // on next backfill run (no data loss).
        //
        // merchant_location_corrections stores user pins — wiping means users will need
        // to re-pin merchants they corrected. Acceptable trade-off: the old pins were
        // keyed with a different strategy and would silently fail lookups anyway.
        val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("DELETE FROM merchant_locations")
                database.execSQL("DELETE FROM merchant_location_corrections")
            }
        }

        // Migration 33 -> 34: Add ai_artifacts table for Phase 1 AI foundation.
        // Stores AI-generated briefings and explanations separately from financial tables
        // so that AI output can be expired, regenerated, or disabled without side effects.
        val MIGRATION_33_34 = object : androidx.room.migration.Migration(33, 34) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_artifacts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        targetType TEXT NOT NULL,
                        targetId INTEGER,
                        targetKey TEXT NOT NULL,
                        capability TEXT NOT NULL,
                        status TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        provider TEXT,
                        modelName TEXT,
                        promptVersion TEXT NOT NULL,
                        summaryText TEXT,
                        explanationText TEXT,
                        payloadJson TEXT,
                        confidence REAL,
                        sourceHash TEXT NOT NULL,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        expiresAt INTEGER
                    )
                """.trimIndent())

                // Unique index for upsert deduplication
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_ai_artifacts_targetKey_capability_promptVersion_sourceHash
                    ON ai_artifacts (targetKey, capability, promptVersion, sourceHash)
                """.trimIndent())

                // Latest-artifact lookup
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS
                    index_ai_artifacts_targetKey_capability_updatedAt
                    ON ai_artifacts (targetKey, capability, updatedAt)
                """.trimIndent())

                // Cleanup sweep by status
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS
                    index_ai_artifacts_status_updatedAt
                    ON ai_artifacts (status, updatedAt)
                """.trimIndent())

                // TTL expiry sweep
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS
                    index_ai_artifacts_expiresAt
                    ON ai_artifacts (expiresAt)
                """.trimIndent())
            }
        }

        val MIGRATION_34_35 = object : androidx.room.migration.Migration(34, 35) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_chat_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ai_chat_sessions_updatedAt
                    ON ai_chat_sessions (updatedAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ai_chat_sessions_createdAt
                    ON ai_chat_sessions (createdAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS ai_chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        text TEXT NOT NULL,
                        payloadJson TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES ai_chat_sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ai_chat_messages_sessionId
                    ON ai_chat_messages (sessionId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ai_chat_messages_sessionId_createdAt
                    ON ai_chat_messages (sessionId, createdAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_ai_chat_messages_createdAt
                    ON ai_chat_messages (createdAt)
                """.trimIndent())
            }
        }

        // Migration 35 -> 36: Add recommendations table for Phase 4B AI Follow-Through
        val MIGRATION_35_36 = object : androidx.room.migration.Migration(35, 36) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS recommendations (
                        id TEXT PRIMARY KEY NOT NULL,
                        userId TEXT NOT NULL,
                        recommendationText TEXT NOT NULL,
                        navigationTarget TEXT NOT NULL,
                        filterCriteria TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        dismissedAt INTEGER,
                        expiresAt INTEGER NOT NULL,
                        priority TEXT NOT NULL,
                        category TEXT NOT NULL,
                        sourceArtifactId TEXT NOT NULL,
                        status TEXT NOT NULL
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recommendations_userId_status_expiresAt
                    ON recommendations (userId, status, expiresAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recommendations_sourceArtifactId
                    ON recommendations (sourceArtifactId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recommendations_createdAt
                    ON recommendations (createdAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recommendations_expiresAt
                    ON recommendations (expiresAt)
                """.trimIndent())
            }
        }

        // Migration 36 -> 37: Add receipt item categorization table and status column
        val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create receipt item categorizations table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS receipt_item_categorizations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        expenseId INTEGER,
                        itemDescription TEXT NOT NULL,
                        itemAmount REAL NOT NULL,
                        suggestedCategoryId INTEGER,
                        suggestedCategoryName TEXT,
                        confidence REAL NOT NULL,
                        aiRationale TEXT,
                        alternativeCategoriesJson TEXT,
                        userCorrectedCategoryId INTEGER,
                        userCorrectedCategoryName TEXT,
                        userCorrectedAt INTEGER,
                        taxAmount REAL,
                        isNewCategorySuggestion INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_receiptId
                    ON receipt_item_categorizations (receiptId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_expenseId
                    ON receipt_item_categorizations (expenseId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_suggestedCategoryId
                    ON receipt_item_categorizations (suggestedCategoryId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_userCorrectedCategoryId
                    ON receipt_item_categorizations (userCorrectedCategoryId)
                """.trimIndent())

                // Add itemCategorizationStatus column to scanned_receipts
                database.execSQL("""
                    ALTER TABLE scanned_receipts 
                    ADD COLUMN itemCategorizationStatus TEXT NOT NULL DEFAULT 'PENDING'
                """.trimIndent())
            }
        }

        // Migration 37 -> 38: Add warranty and return window tables
        val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create warranties table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS warranties (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        expenseId INTEGER,
                        productName TEXT NOT NULL,
                        merchantName TEXT NOT NULL,
                        purchaseDate INTEGER NOT NULL,
                        warrantyDurationMonths INTEGER NOT NULL,
                        warrantyEndDate INTEGER NOT NULL,
                        warrantyType TEXT NOT NULL DEFAULT 'MANUFACTURER',
                        supportPhone TEXT,
                        supportEmail TEXT,
                        warrantyDocumentUrl TEXT,
                        notes TEXT,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        claimedAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // Create indices for warranties
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_warranties_expenseId ON warranties (expenseId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_warranties_warrantyEndDate ON warranties (warrantyEndDate)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_warranties_status ON warranties (status)
                """.trimIndent())

                // Create return_windows table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS return_windows (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        expenseId INTEGER,
                        productName TEXT NOT NULL,
                        merchantName TEXT NOT NULL,
                        purchaseDate INTEGER NOT NULL,
                        returnDays INTEGER NOT NULL,
                        returnDeadline INTEGER NOT NULL,
                        returnPolicyUrl TEXT,
                        returnConditions TEXT,
                        status TEXT NOT NULL DEFAULT 'RETURNABLE',
                        returnedAt INTEGER,
                        refundAmount REAL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // Create indices for return_windows
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_return_windows_receiptId ON return_windows (receiptId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_return_windows_expenseId ON return_windows (expenseId)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_return_windows_returnDeadline ON return_windows (returnDeadline)
                """.trimIndent())
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_return_windows_status ON return_windows (status)
                """.trimIndent())
            }
        }

        // Migration 38 -> 39: Add receipt matching fields
        val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add match status columns to scanned_receipts
                database.execSQL("""
                    ALTER TABLE scanned_receipts 
                    ADD COLUMN matchStatus TEXT NOT NULL DEFAULT 'UNMATCHED'
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE scanned_receipts 
                    ADD COLUMN matchConfidence REAL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE scanned_receipts 
                    ADD COLUMN suggestedExpenseId INTEGER
                """.trimIndent())
                
                // Create index for match status queries
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_scanned_receipts_matchStatus 
                    ON scanned_receipts (matchStatus)
                """.trimIndent())
            }
        }

        // Migration 39 -> 40: Advanced Subscription Management
        val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add subscription-specific columns to manual_recurring_expenses
                database.execSQL("""
                    ALTER TABLE manual_recurring_expenses 
                    ADD COLUMN isSubscription INTEGER NOT NULL DEFAULT 1
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE manual_recurring_expenses 
                    ADD COLUMN subscriptionCategory TEXT DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE manual_recurring_expenses 
                    ADD COLUMN usageTargetPerMonth INTEGER DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE manual_recurring_expenses 
                    ADD COLUMN cancellationUrl TEXT DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE manual_recurring_expenses 
                    ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1
                """.trimIndent())
                
                // 2. Create subscription_price_history table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS subscription_price_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subscriptionId INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        recordedAt INTEGER NOT NULL,
                        changeReason TEXT,
                        FOREIGN KEY(subscriptionId) REFERENCES manual_recurring_expenses(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_subscription_price_history_subscriptionId 
                    ON subscription_price_history (subscriptionId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_subscription_price_history_subscriptionId_recordedAt 
                    ON subscription_price_history (subscriptionId, recordedAt)
                """.trimIndent())
                
                // 3. Create subscription_usage table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS subscription_usage (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subscriptionId INTEGER NOT NULL,
                        usedAt INTEGER NOT NULL,
                        usageDurationMinutes INTEGER,
                        usageType TEXT,
                        FOREIGN KEY(subscriptionId) REFERENCES manual_recurring_expenses(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_subscription_usage_subscriptionId 
                    ON subscription_usage (subscriptionId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_subscription_usage_subscriptionId_usedAt 
                    ON subscription_usage (subscriptionId, usedAt)
                """.trimIndent())
            }
        }

        // Migration 40 -> 41: Business/Personal Separation
        val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Add business expense columns to expenses table
                database.execSQL("""
                    ALTER TABLE expenses 
                    ADD COLUMN isBusinessExpense INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE expenses 
                    ADD COLUMN businessPurpose TEXT DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE expenses 
                    ADD COLUMN businessCategory TEXT DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE expenses 
                    ADD COLUMN businessProject TEXT DEFAULT NULL
                """.trimIndent())
                
                database.execSQL("""
                    ALTER TABLE expenses 
                    ADD COLUMN requiresReceipt INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
                
                // Create index for business expense queries
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_expenses_isBusinessExpense 
                    ON expenses (isBusinessExpense)
                """.trimIndent())
                
                // 2. Create mileage_tracking table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS mileage_tracking (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date INTEGER NOT NULL,
                        startOdometer REAL,
                        endOdometer REAL,
                        distanceKm REAL NOT NULL,
                        startLocation TEXT,
                        endLocation TEXT,
                        startLatitude REAL,
                        startLongitude REAL,
                        endLatitude REAL,
                        endLongitude REAL,
                        isBusinessTrip INTEGER NOT NULL DEFAULT 1,
                        tripPurpose TEXT NOT NULL,
                        businessProject TEXT,
                        clientName TEXT,
                        deductionRatePerKm REAL NOT NULL DEFAULT 0.30,
                        calculatedDeduction REAL,
                        linkedExpenseId INTEGER,
                        fuelCost REAL,
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(linkedExpenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                
                // Create indices for mileage tracking
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_mileage_tracking_linkedExpenseId 
                    ON mileage_tracking (linkedExpenseId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_mileage_tracking_date 
                    ON mileage_tracking (date)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_mileage_tracking_isBusinessTrip 
                    ON mileage_tracking (isBusinessTrip)
                """.trimIndent())
            }
        }

        // Migration 41 -> 42: Multi-Currency Support
        val MIGRATION_41_42 = object : androidx.room.migration.Migration(41, 42) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create exchange_rates table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS exchange_rates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromCurrency TEXT NOT NULL,
                        toCurrency TEXT NOT NULL,
                        rate REAL NOT NULL,
                        lastUpdated INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'manual'
                    )
                """.trimIndent())
                
                // Create unique index for currency pairs
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_exchange_rates_from_to 
                    ON exchange_rates (fromCurrency, toCurrency)
                """.trimIndent())
                
                // Create index for lastUpdated (for cleanup queries)
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_exchange_rates_lastUpdated 
                    ON exchange_rates (lastUpdated)
                """.trimIndent())
                
                // Note: The expenses table already has a 'currency' column
                // No migration needed for that as it's already there
                
                // Insert default EUR rates (base currency)
                val now = System.currentTimeMillis()
                database.execSQL("""
                    INSERT INTO exchange_rates (fromCurrency, toCurrency, rate, lastUpdated, source) VALUES
                    ('USD', 'EUR', 0.92, $now, 'manual'),
                    ('GBP', 'EUR', 1.17, $now, 'manual'),
                    ('EUR', 'USD', 1.09, $now, 'manual'),
                    ('EUR', 'GBP', 0.85, $now, 'manual'),
                    ('EUR', 'EUR', 1.0, $now, 'manual')
                """.trimIndent())
            }
        }

        // Migration 42 -> 43: Shared Expense Groups
        val MIGRATION_42_43 = object : androidx.room.migration.Migration(42, 43) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create expense_groups table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS expense_groups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT,
                        defaultCurrency TEXT NOT NULL DEFAULT 'EUR',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        createdBy TEXT NOT NULL DEFAULT 'me'
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_expense_groups_isActive 
                    ON expense_groups (isActive)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_expense_groups_createdAt 
                    ON expense_groups (createdAt)
                """.trimIndent())
                
                // Create group_members table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        email TEXT,
                        isCurrentUser INTEGER NOT NULL DEFAULT 0,
                        joinedAt INTEGER NOT NULL,
                        FOREIGN KEY(groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_group_members_groupId 
                    ON group_members (groupId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name 
                    ON group_members (groupId, name)
                """.trimIndent())
                
                // Create group_expenses table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_expenses (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER NOT NULL,
                        expenseId INTEGER NOT NULL,
                        paidById INTEGER,
                        date INTEGER NOT NULL,
                        description TEXT NOT NULL,
                        totalAmount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        splitType TEXT NOT NULL DEFAULT 'EQUAL',
                        customSplitsJson TEXT,
                        FOREIGN KEY(groupId) REFERENCES expense_groups(id) ON DELETE CASCADE,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE CASCADE,
                        FOREIGN KEY(paidById) REFERENCES group_members(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_group_expenses_groupId 
                    ON group_expenses (groupId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_group_expenses_expenseId 
                    ON group_expenses (expenseId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_group_expenses_paidById 
                    ON group_expenses (paidById)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_date 
                    ON group_expenses (groupId, date)
                """.trimIndent())
            }
        }

        // Migration 43 -> 44: Budget Forecasting with AI
        val MIGRATION_43_44 = object : androidx.room.migration.Migration(43, 44) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create budget_forecasts table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budget_forecasts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        budgetId INTEGER NOT NULL,
                        forecastDate INTEGER NOT NULL,
                        targetPeriodStart INTEGER NOT NULL,
                        targetPeriodEnd INTEGER NOT NULL,
                        predictedSpending REAL NOT NULL,
                        predictedRemaining REAL NOT NULL,
                        confidenceScore REAL NOT NULL,
                        riskLevel TEXT NOT NULL,
                        overspendProbability REAL NOT NULL,
                        recommendationsJson TEXT,
                        actualSpending REAL,
                        forecastAccuracy REAL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId 
                    ON budget_forecasts (budgetId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate 
                    ON budget_forecasts (forecastDate)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive 
                    ON budget_forecasts (isActive)
                """.trimIndent())
            }
        }

        // Migration 44 -> 45: Investment Tracking
        val MIGRATION_44_45 = object : androidx.room.migration.Migration(44, 45) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create investments table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS investments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        type TEXT NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        exchange TEXT,
                        purchasePrice REAL NOT NULL,
                        quantity REAL NOT NULL,
                        purchaseDate INTEGER NOT NULL,
                        purchaseFees REAL NOT NULL DEFAULT 0.0,
                        currentPrice REAL NOT NULL,
                        lastUpdated INTEGER NOT NULL,
                        category TEXT,
                        notes TEXT,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        targetPrice REAL,
                        stopLossPrice REAL,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_investments_type 
                    ON investments (type)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_investments_symbol 
                    ON investments (symbol)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_investments_isActive 
                    ON investments (isActive)
                """.trimIndent())
                
                // Create investment_values table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS investment_values (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        investmentId INTEGER NOT NULL,
                        price REAL NOT NULL,
                        timestamp INTEGER NOT NULL,
                        totalValue REAL NOT NULL,
                        dayChange REAL,
                        dayChangePercent REAL,
                        FOREIGN KEY(investmentId) REFERENCES investments(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_investment_values_investmentId_timestamp 
                    ON investment_values (investmentId, timestamp)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_investment_values_timestamp 
                    ON investment_values (timestamp)
                """.trimIndent())
            }
        }

        // Migration 45 -> 46: Bank API Integration
        val MIGRATION_45_46 = object : androidx.room.migration.Migration(45, 46) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create bank_connections table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bank_connections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bankId TEXT NOT NULL,
                        bankName TEXT NOT NULL,
                        countryCode TEXT NOT NULL,
                        accessToken TEXT,
                        refreshToken TEXT,
                        tokenEncryptionVersion INTEGER NOT NULL DEFAULT 0,
                        tokenExpiry INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 0,
                        isConnected INTEGER NOT NULL DEFAULT 0,
                        lastSync INTEGER,
                        lastSyncStatus TEXT NOT NULL DEFAULT 'NEVER',
                        autoSync INTEGER NOT NULL DEFAULT 1,
                        syncFrequency TEXT NOT NULL DEFAULT 'DAILY',
                        defaultCategoryId INTEGER,
                        lastError TEXT,
                        lastErrorTime INTEGER,
                        consecutiveErrors INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_bank_connections_bankId 
                    ON bank_connections (bankId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_bank_connections_isActive 
                    ON bank_connections (isActive)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_bank_connections_lastSync 
                    ON bank_connections (lastSync)
                """.trimIndent())
            }
        }

        // Migration 46 -> 47: Enhanced Split Transactions
        val MIGRATION_46_47 = object : androidx.room.migration.Migration(46, 47) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create split_templates table for saved split patterns
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS split_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        totalSplits INTEGER NOT NULL DEFAULT 2,
                        splitType TEXT NOT NULL DEFAULT 'PERCENTAGE',
                        shares TEXT NOT NULL,
                        description TEXT,
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_split_templates_isDefault 
                    ON split_templates (isDefault)
                """.trimIndent())
                
                // Create split_item_assignments for receipt item splitting
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS split_item_assignments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        expenseId INTEGER NOT NULL,
                        receiptItemId INTEGER,
                        participantName TEXT NOT NULL,
                        participantIndex INTEGER NOT NULL DEFAULT 0,
                        assignedAmount REAL NOT NULL,
                        isPaid INTEGER NOT NULL DEFAULT 0,
                        paidAt INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_split_item_assignments_expenseId 
                    ON split_item_assignments (expenseId)
                """.trimIndent())
                
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_split_item_assignments_receiptItemId 
                    ON split_item_assignments (receiptItemId)
                """.trimIndent())
                
                // Add columns to expenses table for enhanced split tracking
                database.execSQL("ALTER TABLE expenses ADD COLUMN splitTemplateId INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE expenses ADD COLUMN splitVisualization TEXT DEFAULT NULL")
            }
        }

        // Migration 47 -> 48: Add missing isBusinessExpense index to align entity with schema
        // This index was created in migration 40->41 but not declared in the entity until now.
        // This migration ensures the index exists for consistency with the entity definition.
        val MIGRATION_47_48 = object : androidx.room.migration.Migration(47, 48) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create the index if it doesn't exist (idempotent operation)
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_expenses_isBusinessExpense 
                    ON expenses (isBusinessExpense)
                """.trimIndent())
            }
        }

        // Migration 48 -> 49: Fix scanned_receipts default values
        // The matchStatus and itemCategorizationStatus columns were added with SQL DEFAULT
        // constraints in earlier migrations, but the entity now uses @ColumnInfo(defaultValue)
        // annotations to match. This migration recreates the table to ensure schema consistency.
        val MIGRATION_48_49 = object : androidx.room.migration.Migration(48, 49) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("PRAGMA foreign_keys=OFF")
                try {
                    database.beginTransaction()
                    try {
                        // Create new table with correct schema including DEFAULT constraints
                        database.execSQL("""
                            CREATE TABLE scanned_receipts_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                imagePath TEXT NOT NULL,
                                rawOcrText TEXT NOT NULL,
                                parsedTotal REAL,
                                parsedMerchant TEXT,
                                parsedDate INTEGER,
                                parsedItems TEXT,
                                parsedTaxAmount REAL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                confidence REAL NOT NULL,
                                expenseId INTEGER,
                                matchStatus TEXT NOT NULL DEFAULT 'UNMATCHED',
                                matchConfidence REAL,
                                suggestedExpenseId INTEGER,
                                createdAt INTEGER NOT NULL,
                                itemCategorizationStatus TEXT NOT NULL DEFAULT 'PENDING',
                                FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                            )
                        """.trimIndent())
                        
                        // Copy data from old table
                        database.execSQL("""
                            INSERT INTO scanned_receipts_new 
                            SELECT id, imagePath, rawOcrText, parsedTotal, parsedMerchant, 
                                   parsedDate, parsedItems, parsedTaxAmount, currency, 
                                   confidence, expenseId, matchStatus, matchConfidence, 
                                   suggestedExpenseId, createdAt, itemCategorizationStatus 
                            FROM scanned_receipts
                        """.trimIndent())
                        
                        // Drop old table
                        database.execSQL("DROP TABLE scanned_receipts")
                        
                        // Rename new table
                        database.execSQL("ALTER TABLE scanned_receipts_new RENAME TO scanned_receipts")
                        
                        // Recreate indices
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_matchStatus ON scanned_receipts (matchStatus)")
                        
                        database.query("PRAGMA foreign_key_check").use { violations ->
                            if (violations.moveToFirst()) {
                                throw IllegalStateException("Migration produced FK violations")
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // Migration 49 -> 50: Comprehensive schema fix for all tables with default value mismatches
        // This migration recreates tables with proper DEFAULT constraints to align with @ColumnInfo annotations
        val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {

                    // Recreate scanned_receipts table with proper DEFAULT constraints (must be first due to FK dependencies)
                    database.execSQL("""
                        CREATE TABLE scanned_receipts_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            imagePath TEXT NOT NULL,
                            rawOcrText TEXT NOT NULL,
                            parsedTotal REAL,
                            parsedMerchant TEXT,
                            parsedDate INTEGER,
                            parsedItems TEXT,
                            parsedTaxAmount REAL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            confidence REAL NOT NULL,
                            expenseId INTEGER,
                            matchStatus TEXT NOT NULL DEFAULT 'UNMATCHED',
                            matchConfidence REAL,
                            suggestedExpenseId INTEGER,
                            createdAt INTEGER NOT NULL,
                            itemCategorizationStatus TEXT NOT NULL DEFAULT 'PENDING',
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("""
                        INSERT INTO scanned_receipts_new 
                        SELECT id, imagePath, rawOcrText, parsedTotal, parsedMerchant, 
                               parsedDate, parsedItems, parsedTaxAmount, currency, 
                               confidence, expenseId, matchStatus, matchConfidence, 
                               suggestedExpenseId, createdAt, itemCategorizationStatus 
                        FROM scanned_receipts
                    """.trimIndent())
                    
                    database.execSQL("DROP TABLE scanned_receipts")
                    database.execSQL("ALTER TABLE scanned_receipts_new RENAME TO scanned_receipts")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_matchStatus ON scanned_receipts (matchStatus)")
                    
                    // Recreate expenses table with proper defaults
                    database.execSQL("""
                        CREATE TABLE expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            amount REAL NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            merchant TEXT NOT NULL,
                            transactionType TEXT NOT NULL,
                            date INTEGER NOT NULL,
                            rawNotificationId INTEGER,
                            categoryId INTEGER,
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN',
                            isManualEntry INTEGER NOT NULL DEFAULT 0,
                            notes TEXT,
                            dedupeKey TEXT,
                            transferDirection TEXT,
                            transferAccountName TEXT,
                            isNotMine INTEGER NOT NULL DEFAULT 0,
                            ownerName TEXT,
                            isSharedExpense INTEGER NOT NULL DEFAULT 0,
                            sharedWithName TEXT,
                            mySharePercentage INTEGER,
                            myShareAmount REAL,
                            latitude REAL,
                            longitude REAL,
                            locationSource TEXT,
                            placeId TEXT,
                            backfillAttempts INTEGER NOT NULL DEFAULT 0,
                            resolvedAddress TEXT,
                            merchantKey TEXT,
                            isBusinessExpense INTEGER NOT NULL DEFAULT 0,
                            businessPurpose TEXT,
                            businessCategory TEXT,
                            businessProject TEXT,
                            requiresReceipt INTEGER NOT NULL DEFAULT 0,
                            splitTemplateId INTEGER,
                            splitVisualization TEXT,
                            FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                            FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    // DB-4: Use explicit column list instead of SELECT *
                    // Ensure column list matches expenses_new CREATE TABLE exactly.
                    // Columns removed: effectiveAmount, originalAmount, originalCurrency,
                    // exchangeRate, receiptId, suggestedMerchantKey (not in CREATE TABLE).
                    // Columns added: createdAt, paymentMethod, isManualEntry,
                    // transferAccountName, ownerName, isSharedExpense, sharedWithName,
                    // mySharePercentage, myShareAmount, placeId, resolvedAddress,
                    // businessPurpose, businessCategory, businessProject, requiresReceipt,
                    // splitVisualization.
                    database.execSQL("""
                        INSERT INTO expenses_new (
                            id, rawNotificationId, merchant, merchantKey, amount, currency,
                            date, transactionType, categoryId, latitude,
                            longitude, locationSource, placeId, notes, isNotMine, isBusinessExpense,
                            transferDirection, transferAccountName, ownerName,
                            isSharedExpense, sharedWithName, mySharePercentage, myShareAmount,
                            resolvedAddress, dedupeKey, backfillAttempts,
                            createdAt, paymentMethod, isManualEntry,
                            businessPurpose, businessCategory, businessProject,
                            requiresReceipt, splitTemplateId, splitVisualization
                        )
                        SELECT
                            id, rawNotificationId, merchant, merchantKey, amount, currency,
                            date, transactionType, categoryId, latitude,
                            longitude, locationSource, placeId, notes, isNotMine, isBusinessExpense,
                            transferDirection, transferAccountName, ownerName,
                            isSharedExpense, sharedWithName, mySharePercentage, myShareAmount,
                            resolvedAddress, dedupeKey, backfillAttempts,
                            createdAt, paymentMethod, isManualEntry,
                            businessPurpose, businessCategory, businessProject,
                            requiresReceipt, splitTemplateId, splitVisualization
                        FROM expenses
                    """.trimIndent())
                    
                    database.execSQL("DROP TABLE expenses")
                    database.execSQL("ALTER TABLE expenses_new RENAME TO expenses")
                    
                    // Recreate indices for expenses
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_rawNotificationId ON expenses (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses (date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_date ON expenses (transactionType, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_categoryId_date ON expenses (transactionType, categoryId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_categoryId_date ON expenses (categoryId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_amount_merchant_date ON expenses (amount, merchant, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchant_date ON expenses (merchant, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_merchant_date ON expenses (transactionType, merchant, date)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_dedupeKey ON expenses (dedupeKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_latitude_longitude ON expenses (latitude, longitude)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchantKey ON expenses (merchantKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_isBusinessExpense ON expenses (isBusinessExpense)")
                    
                    // Recreate categories table with proper DEFAULT for isDefault
                    database.execSQL("""
                        CREATE TABLE categories_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            icon TEXT NOT NULL,
                            color TEXT NOT NULL,
                            isDefault INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO categories_new SELECT id, name, icon, color, isDefault FROM categories")
                    database.execSQL("DROP TABLE categories")
                    database.execSQL("ALTER TABLE categories_new RENAME TO categories")
                    
                    // Recreate budgets table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE budgets_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            categoryId INTEGER,
                            amount REAL NOT NULL,
                            period TEXT NOT NULL,
                            startDate INTEGER NOT NULL,
                            isActive INTEGER NOT NULL DEFAULT 1,
                            notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                            notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                            rollover INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            lastWarningNotifiedAt INTEGER,
                            lastCriticalNotifiedAt INTEGER,
                            lastExceededNotifiedAt INTEGER,
                            FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO budgets_new SELECT id, categoryId, amount, period, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt FROM budgets")
                    database.execSQL("DROP TABLE budgets")
                    database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
                    
                    // Recreate manual_recurring_expenses table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE manual_recurring_expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            merchant TEXT NOT NULL,
                            amount REAL NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            frequency TEXT NOT NULL,
                            nextDate INTEGER NOT NULL,
                            note TEXT,
                            createdAt INTEGER NOT NULL,
                            isSubscription INTEGER NOT NULL DEFAULT 1,
                            subscriptionCategory TEXT,
                            usageTargetPerMonth INTEGER,
                            cancellationUrl TEXT,
                            isActive INTEGER NOT NULL DEFAULT 1
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO manual_recurring_expenses_new SELECT id, merchant, amount, currency, frequency, nextDate, note, createdAt, isSubscription, subscriptionCategory, usageTargetPerMonth, cancellationUrl, isActive FROM manual_recurring_expenses")
                    database.execSQL("DROP TABLE manual_recurring_expenses")
                    database.execSQL("ALTER TABLE manual_recurring_expenses_new RENAME TO manual_recurring_expenses")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isActive_nextDate ON manual_recurring_expenses (isActive, nextDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isSubscription_isActive_nextDate ON manual_recurring_expenses (isSubscription, isActive, nextDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_merchant ON manual_recurring_expenses (merchant)")
                    
                    // Recreate warranties table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE warranties_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            expenseId INTEGER,
                            productName TEXT NOT NULL,
                            merchantName TEXT NOT NULL,
                            purchaseDate INTEGER NOT NULL,
                            warrantyDurationMonths INTEGER NOT NULL,
                            warrantyEndDate INTEGER NOT NULL,
                            warrantyType TEXT NOT NULL DEFAULT 'MANUFACTURER',
                            supportPhone TEXT,
                            supportEmail TEXT,
                            warrantyDocumentUrl TEXT,
                            notes TEXT,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            claimedAt INTEGER,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO warranties_new SELECT id, receiptId, expenseId, productName, merchantName, purchaseDate, warrantyDurationMonths, warrantyEndDate, warrantyType, supportPhone, supportEmail, warrantyDocumentUrl, notes, status, claimedAt, createdAt, updatedAt FROM warranties")
                    database.execSQL("DROP TABLE warranties")
                    database.execSQL("ALTER TABLE warranties_new RENAME TO warranties")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_expenseId ON warranties (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_warrantyEndDate ON warranties (warrantyEndDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_status ON warranties (status)")
                    
                    // Recreate return_windows table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE return_windows_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            expenseId INTEGER,
                            productName TEXT NOT NULL,
                            merchantName TEXT NOT NULL,
                            purchaseDate INTEGER NOT NULL,
                            returnDays INTEGER NOT NULL,
                            returnDeadline INTEGER NOT NULL,
                            returnPolicyUrl TEXT,
                            returnConditions TEXT,
                            status TEXT NOT NULL DEFAULT 'RETURNABLE',
                            returnedAt INTEGER,
                            refundAmount REAL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO return_windows_new SELECT id, receiptId, expenseId, productName, merchantName, purchaseDate, returnDays, returnDeadline, returnPolicyUrl, returnConditions, status, returnedAt, refundAmount, createdAt, updatedAt FROM return_windows")
                    database.execSQL("DROP TABLE return_windows")
                    database.execSQL("ALTER TABLE return_windows_new RENAME TO return_windows")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_receiptId ON return_windows (receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_expenseId ON return_windows (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_returnDeadline ON return_windows (returnDeadline)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_status ON return_windows (status)")
                    
                    // Recreate receipt_item_categorizations table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE receipt_item_categorizations_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            expenseId INTEGER,
                            itemDescription TEXT NOT NULL,
                            itemAmount REAL NOT NULL,
                            suggestedCategoryId INTEGER,
                            suggestedCategoryName TEXT,
                            confidence REAL NOT NULL,
                            aiRationale TEXT,
                            alternativeCategoriesJson TEXT,
                            userCorrectedCategoryId INTEGER,
                            userCorrectedCategoryName TEXT,
                            userCorrectedAt INTEGER,
                            taxAmount REAL,
                            isNewCategorySuggestion INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO receipt_item_categorizations_new SELECT id, receiptId, expenseId, itemDescription, itemAmount, suggestedCategoryId, suggestedCategoryName, confidence, aiRationale, alternativeCategoriesJson, userCorrectedCategoryId, userCorrectedCategoryName, userCorrectedAt, taxAmount, isNewCategorySuggestion, createdAt, updatedAt FROM receipt_item_categorizations")
                    database.execSQL("DROP TABLE receipt_item_categorizations")
                    database.execSQL("ALTER TABLE receipt_item_categorizations_new RENAME TO receipt_item_categorizations")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_receiptId ON receipt_item_categorizations (receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_expenseId ON receipt_item_categorizations (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_suggestedCategoryId ON receipt_item_categorizations (suggestedCategoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_item_categorizations_userCorrectedCategoryId ON receipt_item_categorizations (userCorrectedCategoryId)")
                    
                    // Recreate split_templates table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE split_templates_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            totalSplits INTEGER NOT NULL DEFAULT 2,
                            splitType TEXT NOT NULL DEFAULT 'PERCENTAGE',
                            shares TEXT NOT NULL,
                            description TEXT,
                            isDefault INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            useCount INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO split_templates_new SELECT id, name, totalSplits, splitType, shares, description, isDefault, createdAt, updatedAt, useCount FROM split_templates")
                    database.execSQL("DROP TABLE split_templates")
                    database.execSQL("ALTER TABLE split_templates_new RENAME TO split_templates")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_split_templates_isDefault ON split_templates (isDefault)")
                    
                    // Recreate split_item_assignments table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE split_item_assignments_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            expenseId INTEGER NOT NULL,
                            receiptItemId INTEGER,
                            participantName TEXT NOT NULL,
                            participantIndex INTEGER NOT NULL DEFAULT 0,
                            assignedAmount REAL NOT NULL,
                            isPaid INTEGER NOT NULL DEFAULT 0,
                            paidAt INTEGER,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO split_item_assignments_new SELECT id, expenseId, receiptItemId, participantName, participantIndex, assignedAmount, isPaid, paidAt, createdAt FROM split_item_assignments")
                    database.execSQL("DROP TABLE split_item_assignments")
                    database.execSQL("ALTER TABLE split_item_assignments_new RENAME TO split_item_assignments")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_split_item_assignments_expenseId ON split_item_assignments (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_split_item_assignments_receiptItemId ON split_item_assignments (receiptItemId)")
                    
                    // Recreate bank_connections table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE bank_connections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            bankId TEXT NOT NULL,
                            bankName TEXT NOT NULL,
                            countryCode TEXT NOT NULL,
                            accessToken TEXT,
                            refreshToken TEXT,
                            tokenEncryptionVersion INTEGER NOT NULL DEFAULT 0,
                            tokenExpiry INTEGER,
                            isActive INTEGER NOT NULL DEFAULT 0,
                            isConnected INTEGER NOT NULL DEFAULT 0,
                            lastSync INTEGER,
                            lastSyncStatus TEXT NOT NULL DEFAULT 'NEVER',
                            autoSync INTEGER NOT NULL DEFAULT 1,
                            syncFrequency TEXT NOT NULL DEFAULT 'DAILY',
                            defaultCategoryId INTEGER,
                            lastError TEXT,
                            lastErrorTime INTEGER,
                            consecutiveErrors INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO bank_connections_new SELECT id, bankId, bankName, countryCode, accessToken, refreshToken, 0, tokenExpiry, isActive, isConnected, lastSync, lastSyncStatus, autoSync, syncFrequency, defaultCategoryId, lastError, lastErrorTime, consecutiveErrors, createdAt FROM bank_connections")
                    database.execSQL("DROP TABLE bank_connections")
                    database.execSQL("ALTER TABLE bank_connections_new RENAME TO bank_connections")
                    database.execSQL("""
                        DELETE FROM bank_connections
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM bank_connections
                            GROUP BY bankId
                        )
                    """.trimIndent())
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_connections_bankId ON bank_connections (bankId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_isActive ON bank_connections (isActive)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_lastSync ON bank_connections (lastSync)")
                    
                    // Recreate investments table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE investments_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            symbol TEXT NOT NULL,
                            type TEXT NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            exchange TEXT,
                            purchasePrice REAL NOT NULL,
                            quantity REAL NOT NULL,
                            purchaseDate INTEGER NOT NULL,
                            purchaseFees REAL NOT NULL DEFAULT 0.0,
                            currentPrice REAL NOT NULL,
                            lastUpdated INTEGER NOT NULL,
                            category TEXT,
                            notes TEXT,
                            isActive INTEGER NOT NULL DEFAULT 1,
                            targetPrice REAL,
                            stopLossPrice REAL,
                            createdAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO investments_new SELECT id, name, symbol, type, currency, exchange, purchasePrice, quantity, purchaseDate, purchaseFees, currentPrice, lastUpdated, category, notes, isActive, targetPrice, stopLossPrice, createdAt FROM investments")
                    database.execSQL("DROP TABLE investments")
                    database.execSQL("ALTER TABLE investments_new RENAME TO investments")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_investments_type ON investments (type)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_investments_symbol ON investments (symbol)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_investments_isActive ON investments (isActive)")
                    
                    // Recreate expense_groups table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE expense_groups_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            description TEXT,
                            defaultCurrency TEXT NOT NULL DEFAULT 'EUR',
                            isActive INTEGER NOT NULL DEFAULT 1,
                            createdAt INTEGER NOT NULL,
                            createdBy TEXT NOT NULL DEFAULT 'me'
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO expense_groups_new SELECT id, name, description, defaultCurrency, isActive, createdAt, createdBy FROM expense_groups")
                    database.execSQL("DROP TABLE expense_groups")
                    database.execSQL("ALTER TABLE expense_groups_new RENAME TO expense_groups")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expense_groups_isActive ON expense_groups (isActive)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expense_groups_createdAt ON expense_groups (createdAt)")
                    
                    // Recreate group_members table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE group_members_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId INTEGER NOT NULL,
                            name TEXT NOT NULL,
                            email TEXT,
                            isCurrentUser INTEGER NOT NULL DEFAULT 0,
                            joinedAt INTEGER NOT NULL,
                            FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO group_members_new SELECT id, groupId, name, email, isCurrentUser, joinedAt FROM group_members")
                    database.execSQL("DROP TABLE group_members")
                    database.execSQL("ALTER TABLE group_members_new RENAME TO group_members")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")
                    
                    // Recreate group_expenses table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE group_expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId INTEGER NOT NULL,
                            expenseId INTEGER,
                            paidById INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            description TEXT NOT NULL,
                            totalAmount REAL NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            splitType TEXT NOT NULL DEFAULT 'EQUAL',
                            customSplitsJson TEXT,
                            FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE,
                            FOREIGN KEY (paidById) REFERENCES group_members(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO group_expenses_new SELECT id, groupId, expenseId, paidById, date, description, totalAmount, currency, splitType, customSplitsJson FROM group_expenses")
                    database.execSQL("DROP TABLE group_expenses")
                    database.execSQL("ALTER TABLE group_expenses_new RENAME TO group_expenses")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId ON group_expenses (groupId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_paidById ON group_expenses (paidById)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_date ON group_expenses (groupId, date)")
                    
                    // Recreate pending_reviews table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE pending_reviews_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            rawNotificationId INTEGER,
                            scannedReceiptId INTEGER,
                            suggestedAmount REAL NOT NULL,
                            suggestedCurrency TEXT NOT NULL,
                            suggestedMerchant TEXT NOT NULL,
                            suggestedType TEXT NOT NULL,
                            suggestedCategoryId INTEGER,
                            suggestedDate INTEGER,
                            confidence REAL NOT NULL,
                            matchType TEXT,
                            explanation TEXT,
                            packageName TEXT NOT NULL,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            status TEXT NOT NULL DEFAULT 'PENDING',
                            suggestedDirection TEXT,
                            suggestedAccountName TEXT,
                            suggestedLatitude REAL,
                            suggestedLongitude REAL,
                            FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                            FOREIGN KEY (scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO pending_reviews_new SELECT id, rawNotificationId, scannedReceiptId, suggestedAmount, suggestedCurrency, suggestedMerchant, suggestedType, suggestedCategoryId, suggestedDate, confidence, matchType, explanation, packageName, notificationTitle, notificationText, createdAt, status, suggestedDirection, suggestedAccountName, suggestedLatitude, suggestedLongitude FROM pending_reviews")
                    database.execSQL("DROP TABLE pending_reviews")
                    database.execSQL("ALTER TABLE pending_reviews_new RENAME TO pending_reviews")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                    
                    // Recreate planned_expenses table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE planned_expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            description TEXT NOT NULL,
                            amount REAL NOT NULL,
                            date INTEGER NOT NULL,
                            categoryId INTEGER,
                            isRecurring INTEGER NOT NULL DEFAULT 0,
                            priority TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO planned_expenses_new SELECT id, description, amount, date, categoryId, isRecurring, priority, createdAt FROM planned_expenses")
                    database.execSQL("DROP TABLE planned_expenses")
                    database.execSQL("ALTER TABLE planned_expenses_new RENAME TO planned_expenses")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")
                    
                    // Recreate savings_goals table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE savings_goals_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            targetAmount REAL NOT NULL,
                            currentAmount REAL NOT NULL DEFAULT 0.0,
                            targetDate INTEGER,
                            protectionLevel TEXT NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO savings_goals_new SELECT id, name, targetAmount, currentAmount, targetDate, protectionLevel, createdAt FROM savings_goals")
                    database.execSQL("DROP TABLE savings_goals")
                    database.execSQL("ALTER TABLE savings_goals_new RENAME TO savings_goals")
                    
                    // Recreate source_stats table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE source_stats_new (
                            packageName TEXT PRIMARY KEY NOT NULL,
                            totalNotifications INTEGER NOT NULL DEFAULT 0,
                            acceptedAsExpense INTEGER NOT NULL DEFAULT 0,
                            rejectedByUser INTEGER NOT NULL DEFAULT 0,
                            autoRejected INTEGER NOT NULL DEFAULT 0,
                            pendingReview INTEGER NOT NULL DEFAULT 0,
                            duplicates INTEGER NOT NULL DEFAULT 0,
                            lastSeen INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO source_stats_new SELECT packageName, totalNotifications, acceptedAsExpense, rejectedByUser, autoRejected, pendingReview, duplicates, lastSeen FROM source_stats")
                    database.execSQL("DROP TABLE source_stats")
                    database.execSQL("ALTER TABLE source_stats_new RENAME TO source_stats")
                    
                    // Recreate user_corrections table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE user_corrections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            packageName TEXT NOT NULL,
                            originalMerchant TEXT NOT NULL,
                            correctedMerchant TEXT,
                            originalAmount REAL NOT NULL,
                            correctedAmount REAL,
                            originalCategoryId INTEGER,
                            correctedCategoryId INTEGER,
                            originalType TEXT,
                            correctedType TEXT,
                            wasRejected INTEGER NOT NULL DEFAULT 0,
                            wasApproved INTEGER NOT NULL DEFAULT 0,
                            notificationTitle TEXT,
                            notificationText TEXT,
                            createdAt INTEGER NOT NULL,
                            FOREIGN KEY (originalCategoryId) REFERENCES categories(id) ON DELETE SET NULL,
                            FOREIGN KEY (correctedCategoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO user_corrections_new SELECT id, packageName, originalMerchant, correctedMerchant, originalAmount, correctedAmount, originalCategoryId, correctedCategoryId, originalType, correctedType, wasRejected, wasApproved, notificationTitle, notificationText, createdAt FROM user_corrections")
                    database.execSQL("DROP TABLE user_corrections")
                    database.execSQL("ALTER TABLE user_corrections_new RENAME TO user_corrections")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_originalCategoryId ON user_corrections (originalCategoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_correctedCategoryId ON user_corrections (correctedCategoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_packageName ON user_corrections (packageName)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_wasApproved ON user_corrections (wasApproved)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_user_corrections_wasRejected ON user_corrections (wasRejected)")
                    
                    // Recreate merchant_canonicals table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE merchant_canonicals_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedName TEXT NOT NULL,
                            searchKey TEXT NOT NULL,
                            categoryId INTEGER,
                            totalOccurrences INTEGER NOT NULL DEFAULT 0,
                            totalSpent REAL NOT NULL DEFAULT 0.0,
                            isVerified INTEGER NOT NULL DEFAULT 0,
                            logoUrl TEXT,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_canonicals_new SELECT id, normalizedName, searchKey, categoryId, totalOccurrences, totalSpent, isVerified, logoUrl, createdAt, updatedAt FROM merchant_canonicals")
                    database.execSQL("DROP TABLE merchant_canonicals")
                    database.execSQL("ALTER TABLE merchant_canonicals_new RENAME TO merchant_canonicals")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_canonicals_normalizedName ON merchant_canonicals (normalizedName)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_searchKey ON merchant_canonicals (searchKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_canonicals_categoryId ON merchant_canonicals (categoryId)")
                    
                    // Recreate merchant_aliases table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE merchant_aliases_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            rawName TEXT NOT NULL,
                            normalizedKey TEXT NOT NULL,
                            canonicalId INTEGER NOT NULL,
                            occurrenceCount INTEGER NOT NULL DEFAULT 1,
                            isUserDefined INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            lastUsedAt INTEGER NOT NULL,
                            FOREIGN KEY (canonicalId) REFERENCES merchant_canonicals(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_aliases_new SELECT id, rawName, normalizedKey, canonicalId, occurrenceCount, isUserDefined, createdAt, lastUsedAt FROM merchant_aliases")
                    database.execSQL("DROP TABLE merchant_aliases")
                    database.execSQL("ALTER TABLE merchant_aliases_new RENAME TO merchant_aliases")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_aliases_rawName ON merchant_aliases (rawName)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_normalizedKey ON merchant_aliases (normalizedKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_aliases_canonicalId ON merchant_aliases (canonicalId)")
                    
                    // Recreate merchant_locations table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE merchant_locations_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedMerchantName TEXT NOT NULL,
                            areaKey TEXT DEFAULT 'global',
                            displayName TEXT NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            source TEXT NOT NULL,
                            osmId TEXT,
                            displayAddress TEXT,
                            confidence REAL NOT NULL DEFAULT 1.0,
                            lastResolvedAt INTEGER NOT NULL,
                            hitCount INTEGER NOT NULL DEFAULT 1
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_locations_new SELECT id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, osmId, displayAddress, confidence, lastResolvedAt, hitCount FROM merchant_locations")
                    database.execSQL("DROP TABLE merchant_locations")
                    database.execSQL("ALTER TABLE merchant_locations_new RENAME TO merchant_locations")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName_areaKey ON merchant_locations (normalizedMerchantName, areaKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt ON merchant_locations (lastResolvedAt)")
                    
                    // Recreate merchant_location_corrections table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE merchant_location_corrections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedMerchantName TEXT NOT NULL,
                            correctedLatitude REAL NOT NULL,
                            correctedLongitude REAL NOT NULL,
                            areaLatitude REAL,
                            areaLongitude REAL,
                            areaKey TEXT NOT NULL,
                            areaRadiusKm REAL NOT NULL DEFAULT 5.0,
                            osmId TEXT,
                            displayAddress TEXT,
                            createdAt INTEGER NOT NULL
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_location_corrections_new SELECT id, normalizedMerchantName, correctedLatitude, correctedLongitude, areaLatitude, areaLongitude, areaKey, areaRadiusKm, osmId, displayAddress, createdAt FROM merchant_location_corrections")
                    database.execSQL("DROP TABLE merchant_location_corrections")
                    database.execSQL("ALTER TABLE merchant_location_corrections_new RENAME TO merchant_location_corrections")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_location_corrections_normalizedMerchantName_areaKey ON merchant_location_corrections (normalizedMerchantName, areaKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_location_corrections_createdAt ON merchant_location_corrections (createdAt)")
                    
                    // Recreate merchant_categories table with proper DEFAULT constraints
                    database.execSQL("""
                        CREATE TABLE merchant_categories_new (
                            merchantPattern TEXT PRIMARY KEY NOT NULL,
                            categoryId INTEGER NOT NULL,
                            confidence REAL NOT NULL DEFAULT 1.0,
                            timesUsed INTEGER NOT NULL DEFAULT 1,
                            normalizedCanonicalName TEXT,
                            FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE CASCADE
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_categories_new SELECT merchantPattern, categoryId, confidence, timesUsed, normalizedCanonicalName FROM merchant_categories")
                    database.execSQL("DROP TABLE merchant_categories")
                    database.execSQL("ALTER TABLE merchant_categories_new RENAME TO merchant_categories")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_categories_categoryId ON merchant_categories (categoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_categories_normalizedCanonicalName ON merchant_categories (normalizedCanonicalName)")
                    
                    if (fkEnabled) {
                        val violations = database.query("PRAGMA foreign_key_check")
                        violations.use {
                            if (it.moveToFirst()) {
                                throw IllegalStateException("Migration 49->50 produced FK violations")
                            }
                        }
                    }

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            } finally {
                if (fkEnabled) {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }
        }

        // Migration 50 -> 51: Schema normalization - fix index drift and table defaults
        val MIGRATION_50_51 = object : androidx.room.migration.Migration(50, 51) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // 1. Ensure required index exists on scanned_receipts
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_matchStatus ON scanned_receipts (matchStatus)")
                    
                    // 2. Drop known legacy extra indices (Room drift sources)
                    database.execSQL("DROP INDEX IF EXISTS index_exchange_rates_from_to")
                    database.execSQL("DROP INDEX IF EXISTS index_expenses_transactionType_merchant")
                    database.execSQL("DROP INDEX IF EXISTS index_merchant_location_corrections_merchant_area")
                    database.execSQL("DROP INDEX IF EXISTS index_merchant_locations_normalizedMerchantName")
                    database.execSQL("DROP INDEX IF EXISTS index_subscription_price_history_subscriptionId")
                    database.execSQL("DROP INDEX IF EXISTS index_subscription_usage_subscriptionId")
                    
                    // 3. Recreate canonical indices that may be missing on upgraded DBs
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exchange_rates_fromCurrency_toCurrency ON exchange_rates(fromCurrency, toCurrency)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses(date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp ON raw_notifications(packageName, timestamp)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_capturedAt ON raw_notifications(capturedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_price_history_subscriptionId_recordedAt ON subscription_price_history(subscriptionId, recordedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_usage_subscriptionId_usedAt ON subscription_usage(subscriptionId, usedAt)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName_areaKey ON merchant_locations(normalizedMerchantName, areaKey)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_location_corrections_normalizedMerchantName_areaKey ON merchant_location_corrections(normalizedMerchantName, areaKey)")
                    
                    // 4. Normalize merchant_locations table - remove SQL default from areaKey to match entity
                    database.execSQL("""
                        CREATE TABLE merchant_locations_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedMerchantName TEXT NOT NULL,
                            areaKey TEXT,
                            displayName TEXT NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            source TEXT NOT NULL,
                            osmId TEXT,
                            displayAddress TEXT,
                            confidence REAL NOT NULL DEFAULT 1.0,
                            lastResolvedAt INTEGER NOT NULL,
                            hitCount INTEGER NOT NULL DEFAULT 1
                        )
                    """.trimIndent())
                    
                    database.execSQL("INSERT INTO merchant_locations_new SELECT id, normalizedMerchantName, areaKey, displayName, latitude, longitude, source, osmId, displayAddress, confidence, lastResolvedAt, hitCount FROM merchant_locations")
                    database.execSQL("DROP TABLE merchant_locations")
                    database.execSQL("ALTER TABLE merchant_locations_new RENAME TO merchant_locations")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_locations_normalizedMerchantName_areaKey ON merchant_locations(normalizedMerchantName, areaKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt ON merchant_locations(lastResolvedAt)")
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 51 -> 52: Fix group_expenses payer FK contract.
        // paidById is NOT NULL, so ON DELETE SET NULL is invalid for referential actions.
        // Switch to ON DELETE RESTRICT to preserve financial records and block unsafe member deletes.
        val MIGRATION_51_52 = object : androidx.room.migration.Migration(51, 52) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("""
                        CREATE TABLE group_expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId INTEGER NOT NULL,
                            expenseId INTEGER,
                            paidById INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            description TEXT NOT NULL,
                            totalAmount REAL NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            splitType TEXT NOT NULL DEFAULT 'EQUAL',
                            customSplitsJson TEXT,
                            FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE,
                            FOREIGN KEY (paidById) REFERENCES group_members(id) ON DELETE RESTRICT
                        )
                    """.trimIndent())

                    database.execSQL("""
                        INSERT INTO group_expenses_new (
                            id, groupId, expenseId, paidById, date, description, totalAmount,
                            currency, splitType, customSplitsJson
                        )
                        SELECT
                            id, groupId, expenseId, paidById, date, description, totalAmount,
                            currency, splitType, customSplitsJson
                        FROM group_expenses
                    """.trimIndent())

                    database.execSQL("DROP TABLE group_expenses")
                    database.execSQL("ALTER TABLE group_expenses_new RENAME TO group_expenses")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId ON group_expenses (groupId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_paidById ON group_expenses (paidById)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_date ON group_expenses (groupId, date)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 52 -> 53: Performance index alignment for audit findings.
        val MIGRATION_52_53 = object : androidx.room.migration.Migration(52, 53) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_expenses_latitude_backfillAttempts_date " +
                        "ON expenses(latitude, backfillAttempts, date)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_group_members_groupId_isCurrentUser " +
                        "ON group_members(groupId, isCurrentUser)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_expense_groups_isActive_createdAt " +
                        "ON expense_groups(isActive, createdAt)"
                )
            }
        }

        // Migration 53 -> 54: F1 Receipt → Warranty Pipeline - Add auto-detection fields
        val MIGRATION_53_54 = object : androidx.room.migration.Migration(53, 54) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // Recreate warranties table with new auto-detection columns
                    database.execSQL("""
                        CREATE TABLE warranties_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            expenseId INTEGER,
                            productName TEXT NOT NULL,
                            merchantName TEXT NOT NULL,
                            purchaseDate INTEGER NOT NULL,
                            warrantyDurationMonths INTEGER NOT NULL,
                            warrantyEndDate INTEGER NOT NULL,
                            warrantyType TEXT NOT NULL DEFAULT 'MANUFACTURER',
                            supportPhone TEXT,
                            supportEmail TEXT,
                            warrantyDocumentUrl TEXT,
                            notes TEXT,
                            status TEXT NOT NULL DEFAULT 'ACTIVE',
                            claimedAt INTEGER,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            autoDetected INTEGER NOT NULL DEFAULT 0,
                            extractionConfidence REAL NOT NULL DEFAULT 0.0,
                            extractionSource TEXT NOT NULL DEFAULT 'manual',
                            needsReview INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                        )
                    """.trimIndent())

                    // Copy data from old table
                    database.execSQL("""
                        INSERT INTO warranties_new (
                            id, receiptId, expenseId, productName, merchantName, purchaseDate, 
                            warrantyDurationMonths, warrantyEndDate, warrantyType, supportPhone, 
                            supportEmail, warrantyDocumentUrl, notes, status, claimedAt, 
                            createdAt, updatedAt, autoDetected, extractionConfidence, 
                            extractionSource, needsReview
                        )
                        SELECT 
                            id, receiptId, expenseId, productName, merchantName, purchaseDate, 
                            warrantyDurationMonths, warrantyEndDate, warrantyType, supportPhone, 
                            supportEmail, warrantyDocumentUrl, notes, status, claimedAt, 
                            createdAt, updatedAt, 0, 0.0, 'manual', 0
                        FROM warranties
                    """.trimIndent())

                    database.execSQL("DROP TABLE warranties")
                    database.execSQL("ALTER TABLE warranties_new RENAME TO warranties")
                    
                    // Recreate indices
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_expenseId ON warranties (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_warrantyEndDate ON warranties (warrantyEndDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_status ON warranties (status)")
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 54 -> 55: F11 Shared Expenses Budget Offset - Add reimbursement tracking columns
        val MIGRATION_54_55 = object : androidx.room.migration.Migration(54, 55) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // Recreate group_expenses table with new reimbursement tracking columns
                    database.execSQL("""
                        CREATE TABLE group_expenses_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            groupId INTEGER NOT NULL,
                            expenseId INTEGER,
                            paidById INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            description TEXT NOT NULL,
                            totalAmount REAL NOT NULL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            splitType TEXT NOT NULL DEFAULT 'EQUAL',
                            customSplitsJson TEXT,
                            isReimbursable INTEGER NOT NULL DEFAULT 0,
                            reimbursedAmount REAL NOT NULL DEFAULT 0.0,
                            settledAt INTEGER,
                            myShareAmount REAL,
                            FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE,
                            FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE,
                            FOREIGN KEY (paidById) REFERENCES group_members(id) ON DELETE RESTRICT
                        )
                    """.trimIndent())

                    // Copy data from old table
                    database.execSQL("""
                        INSERT INTO group_expenses_new (
                            id, groupId, expenseId, paidById, date, description, totalAmount,
                            currency, splitType, customSplitsJson, isReimbursable, reimbursedAmount,
                            settledAt, myShareAmount
                        )
                        SELECT
                            id, groupId, expenseId, paidById, date, description, totalAmount,
                            currency, splitType, customSplitsJson, 0, 0.0, NULL, NULL
                        FROM group_expenses
                    """.trimIndent())

                    database.execSQL("DROP TABLE group_expenses")
                    database.execSQL("ALTER TABLE group_expenses_new RENAME TO group_expenses")

                    // Recreate indices
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId ON group_expenses (groupId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_paidById ON group_expenses (paidById)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_date ON group_expenses (groupId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_isReimbursable ON group_expenses (isReimbursable)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 55 -> 56: Add prompt_states table for F12 Lifestyle Inflation -> Savings Goals
        val MIGRATION_55_56 = object : androidx.room.migration.Migration(55, 56) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create prompt_states table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS prompt_states (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        promptType TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        userAction TEXT,
                        actionDetails TEXT,
                        acknowledgedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_states_promptType_createdAt ON prompt_states (promptType, createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_prompt_states_promptType_userAction ON prompt_states (promptType, userAction)")
            }
        }

        // Migration 56 -> 57: F5 Financial Health Score 2.0 - Add health score history tracking
        val MIGRATION_56_57 = object : androidx.room.migration.Migration(56, 57) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create health_score_history table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_score_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        overallScore INTEGER NOT NULL,
                        savingsRateScore INTEGER NOT NULL,
                        runwayScore INTEGER NOT NULL,
                        budgetAdherenceScore INTEGER NOT NULL,
                        billReliabilityScore INTEGER NOT NULL,
                        savingsRateWeight REAL NOT NULL DEFAULT 0.30,
                        runwayWeight REAL NOT NULL DEFAULT 0.25,
                        budgetAdherenceWeight REAL NOT NULL DEFAULT 0.25,
                        billReliabilityWeight REAL NOT NULL DEFAULT 0.20,
                        periodStart INTEGER NOT NULL,
                        periodEnd INTEGER NOT NULL,
                        calculatedAt INTEGER NOT NULL DEFAULT 0,
                        trend TEXT NOT NULL DEFAULT 'STABLE',
                        recommendation TEXT,
                        isSynced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_calculatedAt ON health_score_history (calculatedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_overallScore ON health_score_history (overallScore)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_periodStart_periodEnd ON health_score_history (periodStart, periodEnd)")
            }
        }

        // Migration 57 -> 58: F6 Smart Savings Sweeps - Add savings_sweep_plan table
        val MIGRATION_57_58 = object : androidx.room.migration.Migration(57, 58) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create savings_sweep_plan table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS savings_sweep_plan (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        goalId INTEGER NOT NULL,
                        monthEnd INTEGER NOT NULL,
                        totalUnderspend REAL NOT NULL,
                        riskBuffer REAL NOT NULL,
                        safeSweepAmount REAL NOT NULL,
                        allocatedAmount REAL NOT NULL,
                        allocationPercentage REAL NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        actionedAt INTEGER,
                        notes TEXT,
                        confidence REAL NOT NULL,
                        computedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (goalId) REFERENCES savings_goals (id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_goalId ON savings_sweep_plan (goalId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_monthEnd_status ON savings_sweep_plan (monthEnd, status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_computedAt ON savings_sweep_plan (computedAt)")
            }
        }

        // Migration 58 -> 59: F2 Notification → Subscription Detection - Add subscription_candidates table
        val MIGRATION_58_59 = object : androidx.room.migration.Migration(58, 59) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create subscription_candidates table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS subscription_candidates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        merchant TEXT NOT NULL,
                        canonicalMerchant TEXT NOT NULL,
                        averageAmount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        detectedInterval TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        transactionCount INTEGER NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        estimatedAnnualCost REAL NOT NULL,
                        isConverted INTEGER NOT NULL DEFAULT 0,
                        convertedSubscriptionId INTEGER,
                        userAction TEXT NOT NULL DEFAULT 'pending',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_canonicalMerchant ON subscription_candidates (canonicalMerchant)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_isConverted ON subscription_candidates (isConverted)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_confidence ON subscription_candidates (confidence)")
            }
        }

        // Migration 59 -> 60: F5 Financial Health Score 2.0 - Add health score history tracking
        val MIGRATION_59_60 = object : androidx.room.migration.Migration(59, 60) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create health_score_history table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS health_score_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        overallScore INTEGER NOT NULL,
                        savingsRateScore INTEGER NOT NULL,
                        runwayScore INTEGER NOT NULL,
                        budgetAdherenceScore INTEGER NOT NULL,
                        billReliabilityScore INTEGER NOT NULL,
                        savingsRateWeight REAL NOT NULL DEFAULT 0.30,
                        runwayWeight REAL NOT NULL DEFAULT 0.25,
                        budgetAdherenceWeight REAL NOT NULL DEFAULT 0.25,
                        billReliabilityWeight REAL NOT NULL DEFAULT 0.20,
                        periodStart INTEGER NOT NULL,
                        periodEnd INTEGER NOT NULL,
                        calculatedAt INTEGER NOT NULL DEFAULT 0,
                        trend TEXT NOT NULL DEFAULT 'STABLE',
                        recommendation TEXT,
                        isSynced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_calculatedAt ON health_score_history (calculatedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_overallScore ON health_score_history (overallScore)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_health_score_history_periodStart_periodEnd ON health_score_history (periodStart, periodEnd)")
            }
        }

        // Migration 60 -> 61: F9 AI Budget Autopilot - Add budget adjustment tables
        val MIGRATION_60_61 = object : androidx.room.migration.Migration(60, 61) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create budget_adjustment_recommendations table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budget_adjustment_recommendations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        budgetId INTEGER NOT NULL,
                        categoryId INTEGER,
                        categoryName TEXT NOT NULL,
                        currentBudget REAL NOT NULL,
                        recommendedBudget REAL NOT NULL,
                        delta REAL NOT NULL,
                        deltaPercentage REAL NOT NULL,
                        reason TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        trend TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        generatedAt INTEGER NOT NULL DEFAULT 0,
                        expiresAt INTEGER,
                        appliedAt INTEGER,
                        dismissedAt INTEGER,
                        FOREIGN KEY (budgetId) REFERENCES budgets (id) ON DELETE CASCADE,
                        FOREIGN KEY (categoryId) REFERENCES categories (id) ON DELETE SET NULL
                    )
                """.trimIndent())

                // Create indices for budget_adjustment_recommendations
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_budgetId ON budget_adjustment_recommendations (budgetId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_categoryId ON budget_adjustment_recommendations (categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_status_generatedAt ON budget_adjustment_recommendations (status, generatedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_generatedAt ON budget_adjustment_recommendations (generatedAt)")

                // Create budget_adjustment_events table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budget_adjustment_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        budgetId INTEGER NOT NULL,
                        previousAmount REAL NOT NULL,
                        newAmount REAL NOT NULL,
                        delta REAL NOT NULL,
                        reason TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        appliedAt INTEGER NOT NULL DEFAULT 0,
                        appliedBy TEXT NOT NULL DEFAULT 'autopilot',
                        FOREIGN KEY (budgetId) REFERENCES budgets (id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create indices for budget_adjustment_events
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_budgetId ON budget_adjustment_events (budgetId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_appliedAt ON budget_adjustment_events (appliedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_budgetId_appliedAt ON budget_adjustment_events (budgetId, appliedAt)")
            }
        }

        // Migration 61 -> 62: F8 Financial Stress Forecast - Add stress forecast snapshots table
        val MIGRATION_61_62 = object : androidx.room.migration.Migration(61, 62) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create stress_forecast_snapshots table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS stress_forecast_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        overallRiskLevel TEXT NOT NULL,
                        days30ProjectedBalance REAL NOT NULL,
                        days30MinBalance REAL NOT NULL,
                        days30ProbabilityOfCrunch REAL NOT NULL,
                        days30RiskLevel TEXT NOT NULL,
                        days30RecurringObligations REAL NOT NULL,
                        days30ExpectedIncome REAL NOT NULL,
                        days30DiscretionaryBuffer REAL NOT NULL,
                        days60ProjectedBalance REAL NOT NULL,
                        days60MinBalance REAL NOT NULL,
                        days60ProbabilityOfCrunch REAL NOT NULL,
                        days60RiskLevel TEXT NOT NULL,
                        days60RecurringObligations REAL NOT NULL,
                        days60ExpectedIncome REAL NOT NULL,
                        days60DiscretionaryBuffer REAL NOT NULL,
                        days90ProjectedBalance REAL NOT NULL,
                        days90MinBalance REAL NOT NULL,
                        days90ProbabilityOfCrunch REAL NOT NULL,
                        days90RiskLevel TEXT NOT NULL,
                        days90RecurringObligations REAL NOT NULL,
                        days90ExpectedIncome REAL NOT NULL,
                        days90DiscretionaryBuffer REAL NOT NULL,
                        earliestCrunchDate INTEGER,
                        recommendationsJson TEXT,
                        currentBalance REAL NOT NULL,
                        computedAt INTEGER NOT NULL DEFAULT 0,
                        isSynced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for stress_forecast_snapshots
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_computedAt ON stress_forecast_snapshots (computedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_overallRiskLevel ON stress_forecast_snapshots (overallRiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days30RiskLevel ON stress_forecast_snapshots (days30RiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days60RiskLevel ON stress_forecast_snapshots (days60RiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days90RiskLevel ON stress_forecast_snapshots (days90RiskLevel)")
            }
        }

        // Migration 62 -> 63: F13 Spending Personality Profile - Add personality classification table
        val MIGRATION_62_63 = object : androidx.room.migration.Migration(62, 63) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create spending_personality_profiles table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS spending_personality_profiles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        personalityType TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        featureScoresJson TEXT NOT NULL DEFAULT '{}',
                        explanationJson TEXT NOT NULL DEFAULT '[]',
                        coachingTipsJson TEXT NOT NULL DEFAULT '[]',
                        lastUpdated INTEGER NOT NULL,
                        analysisPeriodStart INTEGER NOT NULL,
                        analysisPeriodEnd INTEGER NOT NULL,
                        transactionCount INTEGER NOT NULL,
                        isViewed INTEGER NOT NULL DEFAULT 0,
                        viewedAt INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_lastUpdated ON spending_personality_profiles (lastUpdated)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_personalityType ON spending_personality_profiles (personalityType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_isActive ON spending_personality_profiles (isActive)")
            }
        }

        // Migration 63 -> 64: F8 Financial Stress Forecast - Add stress forecast snapshots table
        val MIGRATION_63_64 = object : androidx.room.migration.Migration(63, 64) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create stress_forecast_snapshots table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS stress_forecast_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        overallRiskLevel TEXT NOT NULL,
                        days30ProjectedBalance REAL NOT NULL,
                        days30MinBalance REAL NOT NULL,
                        days30ProbabilityOfCrunch REAL NOT NULL,
                        days30RiskLevel TEXT NOT NULL,
                        days30RecurringObligations REAL NOT NULL,
                        days30ExpectedIncome REAL NOT NULL,
                        days30DiscretionaryBuffer REAL NOT NULL,
                        days60ProjectedBalance REAL NOT NULL,
                        days60MinBalance REAL NOT NULL,
                        days60ProbabilityOfCrunch REAL NOT NULL,
                        days60RiskLevel TEXT NOT NULL,
                        days60RecurringObligations REAL NOT NULL,
                        days60ExpectedIncome REAL NOT NULL,
                        days60DiscretionaryBuffer REAL NOT NULL,
                        days90ProjectedBalance REAL NOT NULL,
                        days90MinBalance REAL NOT NULL,
                        days90ProbabilityOfCrunch REAL NOT NULL,
                        days90RiskLevel TEXT NOT NULL,
                        days90RecurringObligations REAL NOT NULL,
                        days90ExpectedIncome REAL NOT NULL,
                        days90DiscretionaryBuffer REAL NOT NULL,
                        earliestCrunchDate INTEGER,
                        recommendationsJson TEXT,
                        currentBalance REAL NOT NULL,
                        computedAt INTEGER NOT NULL DEFAULT 0,
                        isSynced INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Create indices for stress_forecast_snapshots
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_computedAt ON stress_forecast_snapshots (computedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_overallRiskLevel ON stress_forecast_snapshots (overallRiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days30RiskLevel ON stress_forecast_snapshots (days30RiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days60RiskLevel ON stress_forecast_snapshots (days60RiskLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days90RiskLevel ON stress_forecast_snapshots (days90RiskLevel)")
            }
        }

        // Migration 64 -> 65: F14 Email Receipt Ingestion - Add email_receipt_sources table
        val MIGRATION_64_65 = object : androidx.room.migration.Migration(64, 65) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Create email_receipt_sources table for tracking email-based receipts
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS email_receipt_sources (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        emailSender TEXT NOT NULL,
                        emailSubject TEXT NOT NULL,
                        emailMessageId TEXT DEFAULT NULL,
                        parsedAt INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        fingerprint TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY (receiptId) REFERENCES scanned_receipts (id) ON DELETE CASCADE
                    )
                """.trimIndent())

                // Create indices for efficient queries
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources (receiptId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources (emailMessageId) WHERE emailMessageId IS NOT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources (provider, parsedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources (parsedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources (fingerprint)")
            }
        }

        // Migration 65 -> 66: Allow nullable imagePath for email-ingested receipts
        val MIGRATION_65_66 = object : androidx.room.migration.Migration(65, 66) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("PRAGMA foreign_keys=OFF")
                try {
                    database.beginTransaction()
                    try {
                        database.execSQL("""
                            CREATE TABLE scanned_receipts_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                imagePath TEXT,
                                rawOcrText TEXT NOT NULL,
                                parsedTotal REAL,
                                parsedMerchant TEXT,
                                parsedDate INTEGER,
                                parsedItems TEXT,
                                parsedTaxAmount REAL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                confidence REAL NOT NULL,
                                expenseId INTEGER,
                                matchStatus TEXT NOT NULL DEFAULT 'UNMATCHED',
                                matchConfidence REAL,
                                suggestedExpenseId INTEGER,
                                createdAt INTEGER NOT NULL,
                                itemCategorizationStatus TEXT NOT NULL DEFAULT 'PENDING',
                                FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                            )
                        """.trimIndent())

                        database.execSQL("""
                            INSERT INTO scanned_receipts_new
                            SELECT id, imagePath, rawOcrText, parsedTotal, parsedMerchant,
                                   parsedDate, parsedItems, parsedTaxAmount, currency,
                                   confidence, expenseId, matchStatus, matchConfidence,
                                   suggestedExpenseId, createdAt, itemCategorizationStatus
                            FROM scanned_receipts
                        """.trimIndent())

                        database.execSQL("DROP TABLE scanned_receipts")
                        database.execSQL("ALTER TABLE scanned_receipts_new RENAME TO scanned_receipts")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_matchStatus ON scanned_receipts (matchStatus)")

                        database.query("PRAGMA foreign_key_check").use { violations ->
                            if (violations.moveToFirst()) {
                                throw IllegalStateException("Migration produced FK violations")
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // Migration 66 -> 67: F1 warranty de-duplication hardening
        // Enforce one warranty per receipt to avoid check-then-insert races.
        val MIGRATION_66_67 = object : androidx.room.migration.Migration(66, 67) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // Keep only the most recently inserted warranty per receiptId.
                    database.execSQL(
                        """
                        DELETE FROM warranties
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM warranties
                            GROUP BY receiptId
                        )
                        """.trimIndent()
                    )

                    // Replace legacy non-unique index with unique index.
                    database.execSQL("DROP INDEX IF EXISTS index_warranties_receiptId")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 67 -> 68: Repair missing/malformed late-feature tables.
        //
        // Why:
        // - Some upgrade paths could leave new tables in an invalid state
        //   (e.g. anomaly_alerts reported with 0 columns / 0 indices on device).
        // - Some create statements in older migrations also drifted from current
        //   entity contracts (nullable/default/index mismatch).
        //
        // Strategy:
        // - Rebuild the affected tables to match the current Room schema exactly.
        // - Preserve data when source table has the full expected column set.
        // - Recreate canonical indices every time.
        val MIGRATION_67_68 = object : androidx.room.migration.Migration(67, 68) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    repairTable(
                        database = database,
                        tableName = "anomaly_alerts",
                        canonicalColumns = listOf(
                            "id", "expenseId", "merchant", "category", "amount",
                            "anomalyReason", "severity", "alertedAt", "dismissed",
                            "dismissedAt", "userFeedback"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS anomaly_alerts (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                expenseId INTEGER NOT NULL,
                                merchant TEXT NOT NULL,
                                category TEXT,
                                amount REAL NOT NULL,
                                anomalyReason TEXT NOT NULL,
                                severity TEXT NOT NULL,
                                alertedAt INTEGER NOT NULL,
                                dismissed INTEGER NOT NULL DEFAULT 0,
                                dismissedAt INTEGER,
                                userFeedback TEXT
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_anomaly_alerts_expenseId ON anomaly_alerts (expenseId)",
                            "CREATE INDEX IF NOT EXISTS index_anomaly_alerts_merchant_alertedAt ON anomaly_alerts (merchant, alertedAt)",
                            "CREATE INDEX IF NOT EXISTS index_anomaly_alerts_severity_alertedAt ON anomaly_alerts (severity, alertedAt)",
                            "CREATE INDEX IF NOT EXISTS index_anomaly_alerts_dismissed_alertedAt ON anomaly_alerts (dismissed, alertedAt)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "prompt_states",
                        canonicalColumns = listOf(
                            "id", "promptType", "createdAt", "userAction", "actionDetails", "acknowledgedAt"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS prompt_states (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                promptType TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                userAction TEXT,
                                actionDetails TEXT,
                                acknowledgedAt INTEGER DEFAULT 0
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_prompt_states_promptType_createdAt ON prompt_states (promptType, createdAt)",
                            "CREATE INDEX IF NOT EXISTS index_prompt_states_promptType_userAction ON prompt_states (promptType, userAction)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "health_score_history",
                        canonicalColumns = listOf(
                            "id", "overallScore", "savingsRateScore", "runwayScore",
                            "budgetAdherenceScore", "billReliabilityScore", "savingsRateWeight",
                            "runwayWeight", "budgetAdherenceWeight", "billReliabilityWeight",
                            "periodStart", "periodEnd", "calculatedAt", "trend",
                            "recommendation", "isSynced"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS health_score_history (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                overallScore INTEGER NOT NULL,
                                savingsRateScore INTEGER NOT NULL,
                                runwayScore INTEGER NOT NULL,
                                budgetAdherenceScore INTEGER NOT NULL,
                                billReliabilityScore INTEGER NOT NULL,
                                savingsRateWeight REAL NOT NULL DEFAULT 0.30,
                                runwayWeight REAL NOT NULL DEFAULT 0.25,
                                budgetAdherenceWeight REAL NOT NULL DEFAULT 0.25,
                                billReliabilityWeight REAL NOT NULL DEFAULT 0.20,
                                periodStart INTEGER NOT NULL,
                                periodEnd INTEGER NOT NULL,
                                calculatedAt INTEGER NOT NULL DEFAULT 0,
                                trend TEXT NOT NULL DEFAULT 'STABLE',
                                recommendation TEXT,
                                isSynced INTEGER NOT NULL DEFAULT 0
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_health_score_history_calculatedAt ON health_score_history (calculatedAt)",
                            "CREATE INDEX IF NOT EXISTS index_health_score_history_overallScore ON health_score_history (overallScore)",
                            "CREATE INDEX IF NOT EXISTS index_health_score_history_periodStart_periodEnd ON health_score_history (periodStart, periodEnd)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "savings_sweep_plan",
                        canonicalColumns = listOf(
                            "id", "goalId", "monthEnd", "totalUnderspend", "riskBuffer",
                            "safeSweepAmount", "allocatedAmount", "allocationPercentage",
                            "status", "actionedAt", "notes", "confidence", "computedAt"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS savings_sweep_plan (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                goalId INTEGER NOT NULL,
                                monthEnd INTEGER NOT NULL,
                                totalUnderspend REAL NOT NULL,
                                riskBuffer REAL NOT NULL,
                                safeSweepAmount REAL NOT NULL,
                                allocatedAmount REAL NOT NULL,
                                allocationPercentage REAL NOT NULL,
                                status TEXT NOT NULL,
                                actionedAt INTEGER,
                                notes TEXT,
                                confidence REAL NOT NULL,
                                computedAt INTEGER NOT NULL,
                                FOREIGN KEY(goalId) REFERENCES savings_goals(id) ON DELETE CASCADE
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_goalId ON savings_sweep_plan (goalId)",
                            "CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_monthEnd_status ON savings_sweep_plan (monthEnd, status)",
                            "CREATE INDEX IF NOT EXISTS index_savings_sweep_plan_computedAt ON savings_sweep_plan (computedAt)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "subscription_candidates",
                        canonicalColumns = listOf(
                            "id", "merchant", "canonicalMerchant", "averageAmount", "currency",
                            "detectedInterval", "confidence", "transactionCount", "firstSeen",
                            "lastSeen", "estimatedAnnualCost", "isConverted",
                            "convertedSubscriptionId", "userAction", "createdAt", "updatedAt"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS subscription_candidates (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                merchant TEXT NOT NULL,
                                canonicalMerchant TEXT NOT NULL,
                                averageAmount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                detectedInterval TEXT NOT NULL,
                                confidence REAL NOT NULL,
                                transactionCount INTEGER NOT NULL,
                                firstSeen INTEGER NOT NULL,
                                lastSeen INTEGER NOT NULL,
                                estimatedAnnualCost REAL NOT NULL,
                                isConverted INTEGER NOT NULL DEFAULT 0,
                                convertedSubscriptionId INTEGER,
                                userAction TEXT NOT NULL DEFAULT 'pending',
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_subscription_candidates_canonicalMerchant ON subscription_candidates (canonicalMerchant)",
                            "CREATE INDEX IF NOT EXISTS index_subscription_candidates_isConverted ON subscription_candidates (isConverted)",
                            "CREATE INDEX IF NOT EXISTS index_subscription_candidates_confidence ON subscription_candidates (confidence)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "budget_adjustment_recommendations",
                        canonicalColumns = listOf(
                            "id", "budgetId", "categoryId", "categoryName", "currentBudget",
                            "recommendedBudget", "delta", "deltaPercentage", "reason",
                            "confidence", "trend", "status", "generatedAt", "expiresAt",
                            "appliedAt", "dismissedAt"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS budget_adjustment_recommendations (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                budgetId INTEGER NOT NULL,
                                categoryId INTEGER,
                                categoryName TEXT NOT NULL,
                                currentBudget REAL NOT NULL,
                                recommendedBudget REAL NOT NULL,
                                delta REAL NOT NULL,
                                deltaPercentage REAL NOT NULL,
                                reason TEXT NOT NULL,
                                confidence REAL NOT NULL,
                                trend TEXT NOT NULL,
                                status TEXT NOT NULL,
                                generatedAt INTEGER NOT NULL,
                                expiresAt INTEGER,
                                appliedAt INTEGER,
                                dismissedAt INTEGER,
                                FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE CASCADE,
                                FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_budgetId ON budget_adjustment_recommendations (budgetId)",
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_categoryId ON budget_adjustment_recommendations (categoryId)",
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_status_generatedAt ON budget_adjustment_recommendations (status, generatedAt)",
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_recommendations_generatedAt ON budget_adjustment_recommendations (generatedAt)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "budget_adjustment_events",
                        canonicalColumns = listOf(
                            "id", "budgetId", "previousAmount", "newAmount", "delta",
                            "reason", "confidence", "appliedAt", "appliedBy"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS budget_adjustment_events (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                budgetId INTEGER NOT NULL,
                                previousAmount REAL NOT NULL,
                                newAmount REAL NOT NULL,
                                delta REAL NOT NULL,
                                reason TEXT NOT NULL,
                                confidence REAL NOT NULL,
                                appliedAt INTEGER NOT NULL,
                                appliedBy TEXT NOT NULL,
                                FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE CASCADE
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_budgetId ON budget_adjustment_events (budgetId)",
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_appliedAt ON budget_adjustment_events (appliedAt)",
                            "CREATE INDEX IF NOT EXISTS index_budget_adjustment_events_budgetId_appliedAt ON budget_adjustment_events (budgetId, appliedAt)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "spending_personality_profiles",
                        canonicalColumns = listOf(
                            "id", "personalityType", "confidence", "featureScoresJson",
                            "explanationJson", "coachingTipsJson", "lastUpdated",
                            "analysisPeriodStart", "analysisPeriodEnd", "transactionCount",
                            "isViewed", "viewedAt", "isActive"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS spending_personality_profiles (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                personalityType TEXT NOT NULL,
                                confidence REAL NOT NULL,
                                featureScoresJson TEXT NOT NULL DEFAULT '{}',
                                explanationJson TEXT NOT NULL DEFAULT '[]',
                                coachingTipsJson TEXT NOT NULL DEFAULT '[]',
                                lastUpdated INTEGER NOT NULL,
                                analysisPeriodStart INTEGER NOT NULL,
                                analysisPeriodEnd INTEGER NOT NULL,
                                transactionCount INTEGER NOT NULL,
                                isViewed INTEGER NOT NULL DEFAULT 0,
                                viewedAt INTEGER,
                                isActive INTEGER NOT NULL DEFAULT 1
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_lastUpdated ON spending_personality_profiles (lastUpdated)",
                            "CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_personalityType ON spending_personality_profiles (personalityType)",
                            "CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_isActive ON spending_personality_profiles (isActive)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "stress_forecast_snapshots",
                        canonicalColumns = listOf(
                            "id", "overallRiskLevel", "days30ProjectedBalance", "days30MinBalance",
                            "days30ProbabilityOfCrunch", "days30RiskLevel", "days30RecurringObligations",
                            "days30ExpectedIncome", "days30DiscretionaryBuffer", "days60ProjectedBalance",
                            "days60MinBalance", "days60ProbabilityOfCrunch", "days60RiskLevel",
                            "days60RecurringObligations", "days60ExpectedIncome", "days60DiscretionaryBuffer",
                            "days90ProjectedBalance", "days90MinBalance", "days90ProbabilityOfCrunch",
                            "days90RiskLevel", "days90RecurringObligations", "days90ExpectedIncome",
                            "days90DiscretionaryBuffer", "earliestCrunchDate", "recommendationsJson",
                            "currentBalance", "computedAt", "isSynced"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS stress_forecast_snapshots (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                overallRiskLevel TEXT NOT NULL,
                                days30ProjectedBalance REAL NOT NULL,
                                days30MinBalance REAL NOT NULL,
                                days30ProbabilityOfCrunch REAL NOT NULL,
                                days30RiskLevel TEXT NOT NULL,
                                days30RecurringObligations REAL NOT NULL,
                                days30ExpectedIncome REAL NOT NULL,
                                days30DiscretionaryBuffer REAL NOT NULL,
                                days60ProjectedBalance REAL NOT NULL,
                                days60MinBalance REAL NOT NULL,
                                days60ProbabilityOfCrunch REAL NOT NULL,
                                days60RiskLevel TEXT NOT NULL,
                                days60RecurringObligations REAL NOT NULL,
                                days60ExpectedIncome REAL NOT NULL,
                                days60DiscretionaryBuffer REAL NOT NULL,
                                days90ProjectedBalance REAL NOT NULL,
                                days90MinBalance REAL NOT NULL,
                                days90ProbabilityOfCrunch REAL NOT NULL,
                                days90RiskLevel TEXT NOT NULL,
                                days90RecurringObligations REAL NOT NULL,
                                days90ExpectedIncome REAL NOT NULL,
                                days90DiscretionaryBuffer REAL NOT NULL,
                                earliestCrunchDate INTEGER,
                                recommendationsJson TEXT,
                                currentBalance REAL NOT NULL,
                                computedAt INTEGER NOT NULL DEFAULT 0,
                                isSynced INTEGER NOT NULL DEFAULT 0
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_computedAt ON stress_forecast_snapshots (computedAt)",
                            "CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_overallRiskLevel ON stress_forecast_snapshots (overallRiskLevel)",
                            "CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days30RiskLevel ON stress_forecast_snapshots (days30RiskLevel)",
                            "CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days60RiskLevel ON stress_forecast_snapshots (days60RiskLevel)",
                            "CREATE INDEX IF NOT EXISTS index_stress_forecast_snapshots_days90RiskLevel ON stress_forecast_snapshots (days90RiskLevel)"
                        )
                    )

                    repairTable(
                        database = database,
                        tableName = "email_receipt_sources",
                        canonicalColumns = listOf(
                            "id", "receiptId", "emailSender", "emailSubject", "emailMessageId",
                            "parsedAt", "provider", "confidence", "fingerprint"
                        ),
                        createTableSql = """
                            CREATE TABLE IF NOT EXISTS email_receipt_sources (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                receiptId INTEGER NOT NULL,
                                emailSender TEXT NOT NULL,
                                emailSubject TEXT NOT NULL,
                                emailMessageId TEXT DEFAULT NULL,
                                parsedAt INTEGER NOT NULL,
                                provider TEXT NOT NULL,
                                confidence REAL NOT NULL,
                                fingerprint TEXT NOT NULL DEFAULT '',
                                FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE
                            )
                        """.trimIndent(),
                        indexSql = listOf(
                            "CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources (receiptId)",
                            "CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources (emailMessageId) WHERE emailMessageId IS NOT NULL",
                            "CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources (provider, parsedAt)",
                            "CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources (parsedAt)",
                            "CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources (fingerprint)"
                        )
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
            }
        }

        /**
             * Salvatage repair for a corrupted or schema-mismatched table.
             *
             * ## DB-5: Partial salvage limitation
             * The current implementation is **all-or-nothing**: it only copies rows
             * from the old table when **every** canonical column exists in the old
             * table (`oldColumns.containsAll(canonicalColumns.toSet())`). If even
             * one canonical column is missing (e.g. the old table was from a prior
             * schema), all data is silently dropped — only the new empty table survives.
             *
             * Implements **partial salvage**: copies rows for which common columns
             * exist, inserting NULL/defaults for missing columns, and logs the
             * count of salvaged vs dropped rows.
             */
            private fun repairTable(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
                tableName: String,
                canonicalColumns: List<String>,
                createTableSql: String,
                indexSql: List<String>
            ) {
                val tempName = "${tableName}_tmp_67_68"
                val exists = tableExists(database, tableName)

                var oldColumns: Set<String> = emptySet()
                if (exists) {
                    database.execSQL("DROP TABLE IF EXISTS `$tempName`")
                    database.execSQL("ALTER TABLE `$tableName` RENAME TO `$tempName`")
                    oldColumns = readColumnNames(database, tempName)
                }

                database.execSQL(createTableSql)

                if (exists) {
                    val commonColumns = canonicalColumns.filter { it in oldColumns }
                    if (commonColumns.isNotEmpty()) {
                        val columnList = commonColumns.joinToString(", ") { "`$it`" }
                        database.execSQL(
                            "INSERT OR IGNORE INTO `$tableName` ($columnList) " +
                                "SELECT $columnList FROM `$tempName`"
                        )
                        // Count salvaged rows
                        val totalRows = run {
                            var count = 0L
                            database.query("SELECT COUNT(*) FROM `$tempName`").use { c ->
                                if (c.moveToFirst()) count = c.getLong(0)
                            }
                            count
                        }
                        val missingCols = canonicalColumns.filter { it !in oldColumns }
                        android.util.Log.w("DB-REPAIR",
                            "Table '$tableName': partial salvage — ${commonColumns.size}/${canonicalColumns.size} columns matched. " +
                                "Missing: ${missingCols.joinToString()}. Rows in old table: $totalRows."
                        )
                    } else {
                        android.util.Log.e("DB-REPAIR",
                            "Table '$tableName': NO columns in common — ALL data lost! " +
                                "Canonical columns: ${canonicalColumns.joinToString()}. " +
                                "Old columns: ${oldColumns.joinToString()}."
                        )
                    }
                }

                if (exists) {
                    database.execSQL("DROP TABLE IF EXISTS `$tempName`")
                }

                indexSql.forEach { database.execSQL(it) }
            }

            private fun tableExists(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
                tableName: String
            ): Boolean {
                database.query(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$tableName' LIMIT 1"
                ).use { cursor ->
                    return cursor.moveToFirst()
                }
            }

            private fun readColumnNames(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
                tableName: String
            ): Set<String> {
                val names = mutableSetOf<String>()
                database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex < 0) return names
                    while (cursor.moveToNext()) {
                        names += cursor.getString(nameIndex)
                    }
                }
                return names
            }
        }

        // Migration 68 -> 69: Add budget period mode support.
        // Existing budgets default to CALENDAR to preserve established behavior.
        val MIGRATION_68_69 = object : androidx.room.migration.Migration(68, 69) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE budgets ADD COLUMN periodMode TEXT NOT NULL DEFAULT 'ROLLING'")
                database.execSQL("UPDATE budgets SET periodMode = 'CALENDAR'")
            }
        }

        /**
         * Seam used exclusively by tests to force the token-encryption step inside
         * MIGRATION_69_70 to fail deterministically.
         *
         * In production this is always null (the real BankTokenCipher is used).
         * A test may set this to a lambda that throws, which exercises the
         * Keystore-unavailable fallback path, and must reset it to null in tearDown.
         *
         * Visibility: @VisibleForTesting — do not call from production code paths.
         */
        @androidx.annotation.VisibleForTesting
        @Volatile
        var tokenEncryptionOverrideForTest: ((String?) -> String?)? = null

        // Migration 69 -> 70: Security and index hardening.
        // - Encrypt legacy plaintext bank tokens and add token encryption metadata
        // - Enforce uniqueness of bankId with deterministic deduplication
        // - Add missing hot-path indexes (expenses.date, profile.isActive, recurring queries)
        // - Fix emailMessageId nullability and dedupe semantics, add parsedAt index
        val MIGRATION_69_70 = object : androidx.room.migration.Migration(69, 70) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // H12: date-only index used by many ORDER BY / range scans.
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses(date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchantKey_date_amount ON expenses(merchantKey, date, amount)")
                    database.execSQL("ALTER TABLE pending_reviews ADD COLUMN suggestedMerchantKey TEXT")
                    // Intentionally do not backfill suggestedMerchantKey in SQL here.
                    // MerchantKeyGenerator performs richer normalization (e.g., transliteration)
                    // and SQL LOWER/REPLACE would create incompatible keys that block fallback
                    // paths relying on suggestedMerchantKey IS NULL.
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_suggestedMerchantKey ON pending_reviews(suggestedMerchantKey)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_suggestedMerchantKey_suggestedDate ON pending_reviews(status, suggestedMerchantKey, suggestedDate)")

                    // M6: speed up active profile lookup.
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_spending_personality_profiles_isActive ON spending_personality_profiles(isActive)")

                    // M7: recurring expense query coverage.
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isActive_nextDate ON manual_recurring_expenses(isActive, nextDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isSubscription_isActive_nextDate ON manual_recurring_expenses(isSubscription, isActive, nextDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_merchant ON manual_recurring_expenses(merchant)")

                    // H13 + NEW-29: rebuild bank_connections with encryption metadata column.
                    val bankHasTokenVersion = hasColumn(database, "bank_connections", "tokenEncryptionVersion")

                    database.execSQL(
                        """
                        CREATE TABLE bank_connections_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            bankId TEXT NOT NULL,
                            bankName TEXT NOT NULL,
                            countryCode TEXT NOT NULL,
                            accessToken TEXT,
                            refreshToken TEXT,
                            tokenEncryptionVersion INTEGER NOT NULL DEFAULT 0,
                            tokenExpiry INTEGER,
                            isActive INTEGER NOT NULL DEFAULT 0,
                            isConnected INTEGER NOT NULL DEFAULT 0,
                            lastSync INTEGER,
                            lastSyncStatus TEXT NOT NULL DEFAULT 'NEVER',
                            autoSync INTEGER NOT NULL DEFAULT 1,
                            syncFrequency TEXT NOT NULL DEFAULT 'DAILY',
                            defaultCategoryId INTEGER,
                            lastError TEXT,
                            lastErrorTime INTEGER,
                            consecutiveErrors INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )

                    if (bankHasTokenVersion) {
                        database.execSQL(
                            """
                            INSERT INTO bank_connections_new (
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, tokenEncryptionVersion, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            )
                            SELECT
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, tokenEncryptionVersion, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            FROM bank_connections
                            """.trimIndent()
                        )
                    } else {
                        database.execSQL(
                            """
                            INSERT INTO bank_connections_new (
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, tokenEncryptionVersion, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            )
                            SELECT
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, 0, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            FROM bank_connections
                            """.trimIndent()
                        )
                    }

                    database.execSQL("DROP TABLE bank_connections")
                    database.execSQL("ALTER TABLE bank_connections_new RENAME TO bank_connections")

                    // NEW-29: dedupe before unique constraint, keep latest row per bankId.
                    database.execSQL(
                        """
                        DELETE FROM bank_connections
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM bank_connections
                            GROUP BY bankId
                        )
                        """.trimIndent()
                    )

                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_connections_bankId ON bank_connections(bankId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_isActive ON bank_connections(isActive)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_lastSync ON bank_connections(lastSync)")

                    // H13: migrate plaintext tokens to encrypted payloads.
                    // Resilience: If the Android Keystore is unavailable at migration time
                    // (e.g. device just rebooted, StrongBox not accessible, emulator), we
                    // catch the exception per-row and leave that token in plaintext.
                    // tokenEncryptionVersion stays 0 for unencrypted rows so that the
                    // application layer can detect and re-attempt encryption on first use.
                    // Data is never lost — the migration is not rolled back on cipher failure.
                    database.query(
                        "SELECT id, accessToken, refreshToken, tokenEncryptionVersion FROM bank_connections"
                    ).use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow("id")
                        val accessIndex = cursor.getColumnIndexOrThrow("accessToken")
                        val refreshIndex = cursor.getColumnIndexOrThrow("refreshToken")
                        val versionIndex = cursor.getColumnIndexOrThrow("tokenEncryptionVersion")

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idIndex)
                            val access = if (cursor.isNull(accessIndex)) null else cursor.getString(accessIndex)
                            val refresh = if (cursor.isNull(refreshIndex)) null else cursor.getString(refreshIndex)
                            val version = cursor.getInt(versionIndex)

                            // Attempt Keystore-backed encryption; fall back gracefully on failure.
                            // In tests, tokenEncryptionOverrideForTest may replace the real cipher
                            // with a throwing lambda to prove the fallback path deterministically.
                            val encryptedAccess: String?
                            val encryptedRefresh: String?
                            val targetVersion: Int
                            try {
                                val encryptFn = tokenEncryptionOverrideForTest
                                    ?: BankTokenCipher::encryptIfNeeded
                                encryptedAccess = encryptFn(access)
                                encryptedRefresh = encryptFn(refresh)
                                val shouldMarkEncrypted = encryptedAccess != null || encryptedRefresh != null
                                targetVersion = if (shouldMarkEncrypted) 1 else 0
                            } catch (_: Exception) {
                                // Keystore unavailable — leave token as-is; app will re-encrypt later.
                                continue
                            }

                            if (encryptedAccess != access || encryptedRefresh != refresh || version != targetVersion) {
                                database.execSQL(
                                    "UPDATE bank_connections SET accessToken = ?, refreshToken = ?, tokenEncryptionVersion = ? WHERE id = ?",
                                    arrayOf(encryptedAccess, encryptedRefresh, targetVersion, id)
                                )
                            }
                        }
                    }

                    // M8 + NEW-30: rebuild email_receipt_sources with nullable messageId and parsedAt index.
                    database.execSQL(
                        """
                        CREATE TABLE email_receipt_sources_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            emailSender TEXT NOT NULL,
                            emailSubject TEXT NOT NULL,
                            emailMessageId TEXT DEFAULT NULL,
                            parsedAt INTEGER NOT NULL,
                            provider TEXT NOT NULL,
                            confidence REAL NOT NULL,
                            fingerprint TEXT NOT NULL DEFAULT '',
                            FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        INSERT INTO email_receipt_sources_new (
                            id, receiptId, emailSender, emailSubject, emailMessageId,
                            parsedAt, provider, confidence, fingerprint
                        )
                        SELECT
                            id,
                            receiptId,
                            emailSender,
                            emailSubject,
                            NULLIF(TRIM(emailMessageId), ''),
                            parsedAt,
                            provider,
                            confidence,
                            fingerprint
                        FROM email_receipt_sources
                        """.trimIndent()
                    )

                    database.execSQL("DROP TABLE email_receipt_sources")
                    database.execSQL("ALTER TABLE email_receipt_sources_new RENAME TO email_receipt_sources")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources(receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources(provider, parsedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources(parsedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources(fingerprint)")
                    database.execSQL("DROP INDEX IF EXISTS index_email_receipt_sources_emailMessageId")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources(emailMessageId) WHERE emailMessageId IS NOT NULL"
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }

            private fun hasColumn(
                database: androidx.sqlite.db.SupportSQLiteDatabase,
                table: String,
                column: String
            ): Boolean {
                database.query("PRAGMA table_info(`$table`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    if (nameIndex < 0) return false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == column) return true
                    }
                }
                return false
            }
        }

        // Migration 70 -> 71: Group schema integrity — repair legacy duplicate data.
        // - Duplicate current-user rows in group_members are demoted to isCurrentUser = 0
        //   (not deleted — they may be referenced by group_expenses.paidById which has
        //   ON DELETE RESTRICT). The row with the largest id per group is retained as
        //   isCurrentUser = 1.
        // - Legacy duplicate non-null expenseId links in group_expenses are deduplicated,
        //   keeping the row with the smallest id.
        // Trigger-based paidById same-group enforcement is explicitly OUT OF SCOPE.
        val MIGRATION_70_71 = object : androidx.room.migration.Migration(70, 71) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // ── 1. group_members: one current user per group ──────────────

                    // Demotion rule: among duplicate current-user rows for the same
                    // groupId, keep the one with the LARGEST id as isCurrentUser = 1
                    // and demote all others to isCurrentUser = 0.  This avoids
                    // deleting rows that may be referenced by group_expenses.paidById
                    // (ON DELETE RESTRICT) and preserves member data.
                    database.execSQL(
                        """
                        UPDATE group_members
                        SET isCurrentUser = 0
                        WHERE isCurrentUser = 1
                          AND id NOT IN (
                              SELECT MAX(id)
                              FROM group_members
                              WHERE isCurrentUser = 1
                              GROUP BY groupId
                          )
                        """.trimIndent()
                    )

                    // ── 2. group_expenses: heal legacy duplicate non-null expenseId rows ──

                    // Retention rule: among duplicate non-null expenseId rows, keep
                    // the one with the SMALLEST id.
                    database.execSQL(
                        """
                        DELETE FROM group_expenses
                        WHERE expenseId IS NOT NULL
                          AND id NOT IN (
                              SELECT MIN(id)
                              FROM group_expenses
                              WHERE expenseId IS NOT NULL
                              GROUP BY expenseId
                          )
                        """.trimIndent()
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 71 -> 72: B4 — Budget / recurring / category contract cleanup.
        //
        // Budget:
        //  - Demote duplicate active overall budgets (categoryId IS NULL) and
        //    duplicate active per-category budgets, keeping the row with the
        //    largest id as active.
        //
        // Recurring:
        //  - Change the SQL DEFAULT for isSubscription from 1 to 0 on
        //    manual_recurring_expenses. Existing stored rows are preserved.
        //    Requires table rebuild because ALTER TABLE cannot change defaults.
        //  - Child tables subscription_price_history and subscription_usage
        //    reference manual_recurring_expenses(id) with ON DELETE CASCADE.
        //    FK enforcement must be disabled before the transaction so the
        //    table-swap does not cascade-delete child rows.  We verify no FK
        //    violations remain after the rebuild.
        //
        // Category: no schema change — the seeding race fix is purely in Kotlin.
        val MIGRATION_71_72 = object : androidx.room.migration.Migration(71, 72) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Record whether FK enforcement was on so we restore the same state.
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                // Disable FK enforcement BEFORE the transaction.  Some SQLite
                // versions silently ignore PRAGMA foreign_keys changes issued
                // inside an active transaction.
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // ── 1. Budget: demote duplicate active overall budgets ──────────
                        database.execSQL(
                            """
                            UPDATE budgets
                            SET isActive = 0
                            WHERE categoryId IS NULL
                              AND isActive = 1
                              AND id NOT IN (
                                  SELECT MAX(id)
                                  FROM budgets
                                  WHERE categoryId IS NULL AND isActive = 1
                              )
                            """.trimIndent()
                        )

                        // ── 2. Budget: demote duplicate active per-category budgets ─────
                        database.execSQL(
                            """
                            UPDATE budgets
                            SET isActive = 0
                            WHERE categoryId IS NOT NULL
                              AND isActive = 1
                              AND id NOT IN (
                                  SELECT MAX(id)
                                  FROM budgets
                                  WHERE categoryId IS NOT NULL AND isActive = 1
                                  GROUP BY categoryId
                              )
                            """.trimIndent()
                        )

                        // ── 3. Recurring: rebuild table to change isSubscription default ─
                        // FK enforcement is OFF, so dropping the old parent table will not
                        // cascade-delete rows in subscription_price_history or subscription_usage.
                        database.execSQL(
                            """
                            CREATE TABLE manual_recurring_expenses_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                merchant TEXT NOT NULL,
                                amount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                frequency TEXT NOT NULL,
                                nextDate INTEGER NOT NULL,
                                note TEXT,
                                createdAt INTEGER NOT NULL,
                                isSubscription INTEGER NOT NULL DEFAULT 0,
                                subscriptionCategory TEXT,
                                usageTargetPerMonth INTEGER,
                                cancellationUrl TEXT,
                                isActive INTEGER NOT NULL DEFAULT 1
                            )
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            INSERT INTO manual_recurring_expenses_new (
                                id, merchant, amount, currency, frequency, nextDate, note,
                                createdAt, isSubscription, subscriptionCategory,
                                usageTargetPerMonth, cancellationUrl, isActive
                            )
                            SELECT
                                id, merchant, amount, currency, frequency, nextDate, note,
                                createdAt, isSubscription, subscriptionCategory,
                                usageTargetPerMonth, cancellationUrl, isActive
                            FROM manual_recurring_expenses
                            """.trimIndent()
                        )

                        database.execSQL("DROP TABLE manual_recurring_expenses")
                        database.execSQL("ALTER TABLE manual_recurring_expenses_new RENAME TO manual_recurring_expenses")

                        // Recreate indices on manual_recurring_expenses
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isActive_nextDate ON manual_recurring_expenses (isActive, nextDate)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_isSubscription_isActive_nextDate ON manual_recurring_expenses (isSubscription, isActive, nextDate)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_manual_recurring_expenses_merchant ON manual_recurring_expenses (merchant)")

                        // ── 4. Post-rebuild FK verification ─────────────────────────────
                        // Verify that no FK violations were introduced by the rebuild.
                        database.query("PRAGMA foreign_key_check").use { violations ->
                            if (violations.moveToFirst()) {
                                throw IllegalStateException(
                                    "Migration 71→72 produced FK violations"
                                )
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    // Restore FK enforcement to its original state AFTER the transaction.
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // Migration 72 -> 73: B4 Batch 5 — Merchant identity / location / correction hardening.
        //
        // merchant_canonicals:
        //  - Enforce UNIQUE on searchKey (was non-unique index).
        //  - Dedup existing duplicates, keeping the row with the largest id per searchKey.
        //
        // merchant_aliases:
        //  - Enforce UNIQUE on normalizedKey (was non-unique index).
        //  - Dedup existing duplicates, keeping the row with the largest id per normalizedKey.
        //
        // merchant_locations:
        //  - Make areaKey NOT NULL with DEFAULT 'global'.
        //  - Backfill legacy NULL rows to 'global'.
        //  - Dedup collisions created by backfill (prefer higher hitCount, tie-break by larger id).
        //  - Rebuild table to enforce NOT NULL at the schema level.
        //
        // user_corrections:
        //  - No schema change. The DAO-layer fix (REPLACE→ABORT, deterministic tie-breaks)
        //    is purely in Kotlin and does not require a migration step.
        val MIGRATION_72_73 = object : androidx.room.migration.Migration(72, 73) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // ── 1. merchant_canonicals: unique searchKey ─────────────────────
                    // Repoint aliases from discarded canonical ids to the survivor
                    // BEFORE deleting duplicates, so ON DELETE CASCADE does not
                    // silently drop aliases tied to the loser rows.
                    database.execSQL(
                        """
                        UPDATE merchant_aliases
                        SET canonicalId = (
                            SELECT MAX(mc2.id)
                            FROM merchant_canonicals mc2
                            WHERE mc2.searchKey = (
                                SELECT mc3.searchKey
                                FROM merchant_canonicals mc3
                                WHERE mc3.id = merchant_aliases.canonicalId
                            )
                        )
                        WHERE canonicalId NOT IN (
                            SELECT MAX(id)
                            FROM merchant_canonicals
                            GROUP BY searchKey
                        )
                        """.trimIndent()
                    )

                    // Dedup: keep the row with the largest id per searchKey.
                    database.execSQL(
                        """
                        DELETE FROM merchant_canonicals
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM merchant_canonicals
                            GROUP BY searchKey
                        )
                        """.trimIndent()
                    )

                    // Drop the old non-unique index and create a unique one.
                    database.execSQL("DROP INDEX IF EXISTS index_merchant_canonicals_searchKey")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_canonicals_searchKey " +
                            "ON merchant_canonicals (searchKey)"
                    )

                    // ── 2. merchant_aliases: unique normalizedKey ────────────────────

                    // Dedup: keep the row with the largest id per normalizedKey.
                    database.execSQL(
                        """
                        DELETE FROM merchant_aliases
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM merchant_aliases
                            GROUP BY normalizedKey
                        )
                        """.trimIndent()
                    )

                    // Drop the old non-unique index and create a unique one.
                    database.execSQL("DROP INDEX IF EXISTS index_merchant_aliases_normalizedKey")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_merchant_aliases_normalizedKey " +
                            "ON merchant_aliases (normalizedKey)"
                    )

                    // ── 3. merchant_locations: non-null areaKey ──────────────────────

                    // Normalize legacy "<key>|global" areaKey values to plain "global".
                    database.execSQL(
                        "UPDATE merchant_locations SET areaKey = 'global' WHERE areaKey LIKE '%|global'"
                    )

                    // Backfill NULL areaKey rows to 'global' before rebuild.
                    database.execSQL(
                        "UPDATE merchant_locations SET areaKey = 'global' WHERE areaKey IS NULL"
                    )

                    // Dedup collisions: after backfilling NULL→'global', rows may collide
                    // on (normalizedMerchantName, areaKey).
                    // Retain the row with the highest hitCount; tie-break by largest id.
                    database.execSQL(
                        """
                        DELETE FROM merchant_locations
                        WHERE id NOT IN (
                            SELECT id FROM (
                                SELECT MAX(
                                    CASE
                                        WHEN hitCount = maxHit THEN id
                                        ELSE 0
                                    END
                                ) AS id
                                FROM (
                                    SELECT ml.id, ml.normalizedMerchantName, ml.areaKey,
                                           ml.hitCount,
                                           max_tbl.maxHit
                                    FROM merchant_locations ml
                                    INNER JOIN (
                                        SELECT normalizedMerchantName, areaKey, MAX(hitCount) AS maxHit
                                        FROM merchant_locations
                                        GROUP BY normalizedMerchantName, areaKey
                                    ) max_tbl ON ml.normalizedMerchantName = max_tbl.normalizedMerchantName
                                              AND ml.areaKey = max_tbl.areaKey
                                )
                                GROUP BY normalizedMerchantName, areaKey
                            )
                        )
                        """.trimIndent()
                    )

                    // Rebuild table with areaKey NOT NULL DEFAULT 'global'.
                    database.execSQL(
                        """
                        CREATE TABLE merchant_locations_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            normalizedMerchantName TEXT NOT NULL,
                            areaKey TEXT NOT NULL DEFAULT 'global',
                            displayName TEXT NOT NULL,
                            latitude REAL NOT NULL,
                            longitude REAL NOT NULL,
                            source TEXT NOT NULL,
                            osmId TEXT,
                            displayAddress TEXT,
                            confidence REAL NOT NULL DEFAULT 1.0,
                            lastResolvedAt INTEGER NOT NULL,
                            hitCount INTEGER NOT NULL DEFAULT 1
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        INSERT INTO merchant_locations_new (
                            id, normalizedMerchantName, areaKey, displayName,
                            latitude, longitude, source, osmId, displayAddress,
                            confidence, lastResolvedAt, hitCount
                        )
                        SELECT
                            id, normalizedMerchantName, areaKey, displayName,
                            latitude, longitude, source, osmId, displayAddress,
                            confidence, lastResolvedAt, hitCount
                        FROM merchant_locations
                        """.trimIndent()
                    )

                    database.execSQL("DROP TABLE merchant_locations")
                    database.execSQL("ALTER TABLE merchant_locations_new RENAME TO merchant_locations")

                    // Recreate indices on merchant_locations.
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "index_merchant_locations_normalizedMerchantName_areaKey " +
                            "ON merchant_locations (normalizedMerchantName, areaKey)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_merchant_locations_lastResolvedAt " +
                            "ON merchant_locations (lastResolvedAt)"
                    )

                    // ── 4. merchant_location_corrections: normalize legacy global areaKey ─
                    // Older code wrote areaKey as "<merchant>|global" for global corrections.
                    // v73 runtime writes plain "global", so both forms can coexist for the
                    // same merchant, defeating the composite unique key.  Normalize here.

                    // Normalize legacy "<anything>|global" to plain "global".
                    database.execSQL(
                        "UPDATE merchant_location_corrections SET areaKey = 'global' WHERE areaKey LIKE '%|global'"
                    )

                    // Dedup collisions: after normalizing, rows may collide on
                    // (normalizedMerchantName, areaKey).
                    // Retain the row with the largest id (newest correction wins).
                    database.execSQL(
                        """
                        DELETE FROM merchant_location_corrections
                        WHERE id NOT IN (
                            SELECT MAX(id)
                            FROM merchant_location_corrections
                            GROUP BY normalizedMerchantName, areaKey
                        )
                        """.trimIndent()
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 73 -> 74: B4 Batch 6 — Bank / email / notification / alert hardening.
        //
        // bank_connections:
        //  - Add FK from defaultCategoryId → categories(id) ON DELETE SET NULL.
        //  - Add supporting index on defaultCategoryId.
        //  - Table rebuild required because ALTER TABLE cannot add FK constraints.
        //
        // email_receipt_sources:
        //  - REPLACE→ABORT/IGNORE change is DAO-only — no schema migration needed.
        //    The emailMessageId unique index already exists (partial, WHERE NOT NULL).
        //
        // raw_notifications:
        //  - Dedup legacy rows that slipped through the old 4-column unique index
        //    because SQLite treats NULL != NULL.
        //  - Drop any legacy unique / non-Room dedup indexes.
        //  - Recreate only the Room-declared non-unique indexes so long-hop imports
        //    validate cleanly at schema 74.
        //
        // anomaly_alerts:
        //  - Add FK from expenseId → expenses(id) ON DELETE CASCADE.
        //  - Delete orphaned rows before adding FK.
        //  - Table rebuild required.
        val MIGRATION_73_74 = object : androidx.room.migration.Migration(73, 74) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // FK enforcement must be disabled before table rebuilds to avoid
                // cascade side-effects during the rename-swap pattern.
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // ── 1. bank_connections: add FK + index for defaultCategoryId ──

                        // Clean orphaned defaultCategoryId references before adding FK.
                        database.execSQL(
                            """
                            UPDATE bank_connections
                            SET defaultCategoryId = NULL
                            WHERE defaultCategoryId IS NOT NULL
                              AND defaultCategoryId NOT IN (SELECT id FROM categories)
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            CREATE TABLE bank_connections_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                bankId TEXT NOT NULL,
                                bankName TEXT NOT NULL,
                                countryCode TEXT NOT NULL,
                                accessToken TEXT,
                                refreshToken TEXT,
                                tokenEncryptionVersion INTEGER NOT NULL DEFAULT 0,
                                tokenExpiry INTEGER,
                                isActive INTEGER NOT NULL DEFAULT 0,
                                isConnected INTEGER NOT NULL DEFAULT 0,
                                lastSync INTEGER,
                                lastSyncStatus TEXT NOT NULL DEFAULT 'NEVER',
                                autoSync INTEGER NOT NULL DEFAULT 1,
                                syncFrequency TEXT NOT NULL DEFAULT 'DAILY',
                                defaultCategoryId INTEGER,
                                lastError TEXT,
                                lastErrorTime INTEGER,
                                consecutiveErrors INTEGER NOT NULL DEFAULT 0,
                                createdAt INTEGER NOT NULL,
                                FOREIGN KEY (defaultCategoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            INSERT INTO bank_connections_new (
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, tokenEncryptionVersion, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            )
                            SELECT
                                id, bankId, bankName, countryCode,
                                accessToken, refreshToken, tokenEncryptionVersion, tokenExpiry,
                                isActive, isConnected, lastSync, lastSyncStatus,
                                autoSync, syncFrequency, defaultCategoryId,
                                lastError, lastErrorTime, consecutiveErrors, createdAt
                            FROM bank_connections
                            """.trimIndent()
                        )

                        database.execSQL("DROP TABLE bank_connections")
                        database.execSQL("ALTER TABLE bank_connections_new RENAME TO bank_connections")

                        // Recreate indices.
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_connections_bankId ON bank_connections(bankId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_isActive ON bank_connections(isActive)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_lastSync ON bank_connections(lastSync)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_connections_defaultCategoryId ON bank_connections(defaultCategoryId)")

                        // ── 2. raw_notifications: dedup legacy rows, restore Room indexes ──

                        // Dedup rows with NULL title AND NULL text that share the same
                        // packageName+timestamp. Keep the row with the smallest id.
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE title IS NULL AND text IS NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE title IS NULL AND text IS NULL
                                  GROUP BY packageName, timestamp
                              )
                            """.trimIndent()
                        )

                        // Also dedup rows with NULL title (text NOT NULL) — same loophole.
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE title IS NULL AND text IS NOT NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE title IS NULL AND text IS NOT NULL
                                  GROUP BY packageName, timestamp, text
                              )
                            """.trimIndent()
                        )

                        // Dedup rows with NULL text (title NOT NULL).
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE text IS NULL AND title IS NOT NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE text IS NULL AND title IS NOT NULL
                                  GROUP BY packageName, timestamp, title
                              )
                            """.trimIndent()
                        )

                        // Drop legacy unique / non-Room indexes so schema 74 matches
                        // the current Room-declared contract during long-hop imports.
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_packageName_timestamp_title_text")
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_nonnull")
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_both_null")
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_title_null")
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_text_null")

                        // Recreate only the Room-declared non-unique indexes.
                        database.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "index_raw_notifications_packageName_timestamp_title_text " +
                                "ON raw_notifications (packageName, timestamp, title, text)"
                        )
                        database.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "index_raw_notifications_packageName_timestamp " +
                                "ON raw_notifications (packageName, timestamp)"
                        )
                        database.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "index_raw_notifications_capturedAt " +
                                "ON raw_notifications (capturedAt)"
                        )
                        database.execSQL(
                            "CREATE INDEX IF NOT EXISTS " +
                                "index_raw_notifications_isRelevant " +
                                "ON raw_notifications (isRelevant)"
                        )

                        // ── 3. anomaly_alerts: add FK to expenses ─────────────────────

                        // Delete orphaned alerts whose expense no longer exists.
                        database.execSQL(
                            """
                            DELETE FROM anomaly_alerts
                            WHERE expenseId NOT IN (SELECT id FROM expenses)
                            """.trimIndent()
                        )

                        // Rebuild table with FK constraint.
                        database.execSQL(
                            """
                            CREATE TABLE anomaly_alerts_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                expenseId INTEGER NOT NULL,
                                merchant TEXT NOT NULL,
                                category TEXT,
                                amount REAL NOT NULL,
                                anomalyReason TEXT NOT NULL,
                                severity TEXT NOT NULL,
                                alertedAt INTEGER NOT NULL,
                                dismissed INTEGER NOT NULL DEFAULT 0,
                                dismissedAt INTEGER,
                                userFeedback TEXT,
                                FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            INSERT INTO anomaly_alerts_new (
                                id, expenseId, merchant, category, amount,
                                anomalyReason, severity, alertedAt, dismissed,
                                dismissedAt, userFeedback
                            )
                            SELECT
                                id, expenseId, merchant, category, amount,
                                anomalyReason, severity, alertedAt, dismissed,
                                dismissedAt, userFeedback
                            FROM anomaly_alerts
                            """.trimIndent()
                        )

                        database.execSQL("DROP TABLE anomaly_alerts")
                        database.execSQL("ALTER TABLE anomaly_alerts_new RENAME TO anomaly_alerts")

                        // Recreate indices.
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_alerts_expenseId ON anomaly_alerts(expenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_alerts_merchant_alertedAt ON anomaly_alerts(merchant, alertedAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_alerts_severity_alertedAt ON anomaly_alerts(severity, alertedAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_alerts_dismissed_alertedAt ON anomaly_alerts(dismissed, alertedAt)")

                        // ── 4. Post-rebuild FK verification ───────────────────────────
                        if (fkWasEnabled) {
                            database.query("PRAGMA foreign_key_check").use { violations ->
                                if (violations.moveToFirst()) {
                                    throw IllegalStateException(
                                        "Migration 73→74 produced FK violations"
                                    )
                                }
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // ── Migration 74 → 75 ───────────────────────────────────────────────────
        // Batch 7: subscription_candidates healing + budget_forecasts healing.
        //
        //  subscription_candidates:
        //  - Dedup pending candidates with duplicate (canonicalMerchant, detectedInterval).
        //  - Ensure only Room-declared indexes exist.
        //
        //  budget_forecasts:
        //  - Demote duplicate active forecasts for the same (budgetId, targetPeriodStart,
        //    targetPeriodEnd) by setting isActive = 0 on all but the latest (MAX id).
        //  - Ensure Room-declared indexes exist.
        val MIGRATION_74_75 = object : androidx.room.migration.Migration(74, 75) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // ── 1. subscription_candidates: dedup pending duplicates ──────

                    // Among pending candidates (isConverted = 0 AND userAction = 'pending')
                    // with the same (canonicalMerchant, detectedInterval), delete all but
                    // the row with the largest id.
                    database.execSQL(
                        """
                        DELETE FROM subscription_candidates
                        WHERE isConverted = 0 AND userAction = 'pending'
                          AND id NOT IN (
                              SELECT MAX(id)
                              FROM subscription_candidates
                              WHERE isConverted = 0 AND userAction = 'pending'
                              GROUP BY canonicalMerchant, detectedInterval
                          )
                        """.trimIndent()
                    )

                    database.execSQL("DROP INDEX IF EXISTS index_subscription_candidates_pending_merchant_interval")
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_subscription_candidates_canonicalMerchant ON subscription_candidates (canonicalMerchant)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_subscription_candidates_isConverted ON subscription_candidates (isConverted)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_subscription_candidates_confidence ON subscription_candidates (confidence)"
                    )

                    // ── 2. budget_forecasts: demote duplicate active forecasts ────

                    // Among active forecasts (isActive = 1) with the same
                    // (budgetId, targetPeriodStart, targetPeriodEnd), demote all but
                    // the row with the largest id to isActive = 0.
                    database.execSQL(
                        """
                        UPDATE budget_forecasts
                        SET isActive = 0
                        WHERE isActive = 1
                          AND id NOT IN (
                              SELECT MAX(id)
                              FROM budget_forecasts
                              WHERE isActive = 1
                              GROUP BY budgetId, targetPeriodStart, targetPeriodEnd
                          )
                        """.trimIndent()
                    )

                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId ON budget_forecasts (budgetId)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate ON budget_forecasts (forecastDate)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive ON budget_forecasts (isActive)"
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // ─── Migration 75 → 76 (B4 Batch 8): Financial / auxiliary contract wave ────
        //
        // Adds DB-level CHECK constraints and FK hardening for:
        //   savings_goals   — targetAmount > 0, currentAmount >= 0
        //   mileage_tracking — distanceKm > 0, deductionRatePerKm >= 0,
        //                      odometer ordering, fuelCost >= 0
        //   pending_reviews  — suggestedAmount > 0, suggestedType ∈ known enum set
        //   expenses         — splitTemplateId FK → split_templates(id) ON DELETE SET NULL
        //   budgets          — amount > 0, threshold ordering (warning ≤ critical)
        //
        // Each table requiring new CHECK constraints is rebuilt (rename-swap) because
        // CHECK constraints are part of CREATE TABLE DDL in SQLite and cannot be added
        // via ALTER TABLE.
        //
        // Data cleanup runs BEFORE constraint addition to avoid migration failures:
        //   - savings_goals:   clamp targetAmount ≤ 0 → 0.01; clamp currentAmount < 0 → 0
        //   - mileage_tracking: clamp distanceKm ≤ 0 → 0.01; clamp deductionRatePerKm < 0 → 0;
        //                       swap inverted odometers; clamp fuelCost < 0 → 0
        //   - pending_reviews:  coerce unknown suggestedType → 'UNKNOWN';
        //                       clamp suggestedAmount ≤ 0 → 0.01
        //   - expenses:         orphan splitTemplateId → NULL (FK cleanup)
        //   - budgets:          clamp amount ≤ 0 → 0.01; fix threshold ordering
        val MIGRATION_75_76 = object : androidx.room.migration.Migration(75, 76) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // FK enforcement must be OFF for table rebuilds.
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // ── 1. savings_goals: data cleanup + rebuild with CHECKs ────────

                        // Clamp invalid values
                        database.execSQL("UPDATE savings_goals SET targetAmount = 0.01 WHERE targetAmount <= 0")
                        database.execSQL("UPDATE savings_goals SET currentAmount = 0 WHERE currentAmount < 0")

                        database.execSQL(
                            """
                            CREATE TABLE savings_goals_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                targetAmount REAL NOT NULL CHECK(targetAmount > 0),
                                currentAmount REAL NOT NULL DEFAULT 0.0 CHECK(currentAmount >= 0),
                                targetDate INTEGER,
                                protectionLevel TEXT NOT NULL,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO savings_goals_new (id, name, targetAmount, currentAmount, targetDate, protectionLevel, createdAt)
                            SELECT id, name, targetAmount, currentAmount, targetDate, protectionLevel, createdAt
                            FROM savings_goals
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE savings_goals")
                        database.execSQL("ALTER TABLE savings_goals_new RENAME TO savings_goals")

                        // ── 2. mileage_tracking: data cleanup + rebuild with CHECKs ────

                        // Clamp invalid distances and rates
                        database.execSQL("UPDATE mileage_tracking SET distanceKm = 0.01 WHERE distanceKm <= 0")
                        database.execSQL("UPDATE mileage_tracking SET deductionRatePerKm = 0 WHERE deductionRatePerKm < 0")
                        database.execSQL("UPDATE mileage_tracking SET fuelCost = 0 WHERE fuelCost IS NOT NULL AND fuelCost < 0")

                        // Fix inverted odometer pairs: swap when both present and end < start
                        database.execSQL(
                            """
                            UPDATE mileage_tracking
                            SET startOdometer = endOdometer,
                                endOdometer = startOdometer
                            WHERE startOdometer IS NOT NULL
                              AND endOdometer IS NOT NULL
                              AND endOdometer < startOdometer
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            CREATE TABLE mileage_tracking_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                date INTEGER NOT NULL,
                                startOdometer REAL,
                                endOdometer REAL,
                                distanceKm REAL NOT NULL CHECK(distanceKm > 0),
                                startLocation TEXT,
                                endLocation TEXT,
                                startLatitude REAL,
                                startLongitude REAL,
                                endLatitude REAL,
                                endLongitude REAL,
                        isBusinessTrip INTEGER NOT NULL,
                                tripPurpose TEXT NOT NULL,
                                businessProject TEXT,
                                clientName TEXT,
                        deductionRatePerKm REAL NOT NULL CHECK(deductionRatePerKm >= 0),
                                calculatedDeduction REAL,
                                linkedExpenseId INTEGER,
                                fuelCost REAL CHECK(fuelCost IS NULL OR fuelCost >= 0),
                                notes TEXT,
                                createdAt INTEGER NOT NULL,
                                CHECK(endOdometer IS NULL OR startOdometer IS NULL OR endOdometer >= startOdometer),
                                FOREIGN KEY(linkedExpenseId) REFERENCES expenses(id) ON DELETE SET NULL
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO mileage_tracking_new (
                                id, date, startOdometer, endOdometer, distanceKm,
                                startLocation, endLocation, startLatitude, startLongitude,
                                endLatitude, endLongitude, isBusinessTrip, tripPurpose,
                                businessProject, clientName, deductionRatePerKm,
                                calculatedDeduction, linkedExpenseId, fuelCost, notes, createdAt
                            )
                            SELECT
                                id, date, startOdometer, endOdometer, distanceKm,
                                startLocation, endLocation, startLatitude, startLongitude,
                                endLatitude, endLongitude, isBusinessTrip, tripPurpose,
                                businessProject, clientName, deductionRatePerKm,
                                calculatedDeduction, linkedExpenseId, fuelCost, notes, createdAt
                            FROM mileage_tracking
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE mileage_tracking")
                        database.execSQL("ALTER TABLE mileage_tracking_new RENAME TO mileage_tracking")

                        // Recreate mileage_tracking indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_linkedExpenseId ON mileage_tracking (linkedExpenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_date ON mileage_tracking (date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_isBusinessTrip ON mileage_tracking (isBusinessTrip)")

                        // ── 3. pending_reviews: data cleanup + rebuild with CHECKs ──────

                        // Coerce unknown suggestedType values to 'UNKNOWN'
                        database.execSQL(
                            """
                            UPDATE pending_reviews
                            SET suggestedType = 'UNKNOWN'
                            WHERE suggestedType NOT IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER', 'DEPOSIT', 'UNKNOWN')
                            """.trimIndent()
                        )
                        // Clamp non-positive suggestedAmount
                        database.execSQL("UPDATE pending_reviews SET suggestedAmount = 0.01 WHERE suggestedAmount <= 0")

                        database.execSQL(
                            """
                            CREATE TABLE pending_reviews_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                rawNotificationId INTEGER,
                                scannedReceiptId INTEGER,
                        suggestedAmount REAL,
                                suggestedCurrency TEXT NOT NULL,
                                suggestedMerchant TEXT NOT NULL,
                                suggestedMerchantKey TEXT,
                                suggestedType TEXT NOT NULL CHECK(suggestedType IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER', 'DEPOSIT', 'UNKNOWN')),
                                suggestedCategoryId INTEGER,
                                suggestedDate INTEGER,
                                confidence REAL NOT NULL,
                                matchType TEXT,
                                explanation TEXT,
                                packageName TEXT NOT NULL,
                                notificationTitle TEXT,
                                notificationText TEXT,
                                createdAt INTEGER NOT NULL,
                                status TEXT NOT NULL DEFAULT 'PENDING',
                                suggestedDirection TEXT,
                                suggestedAccountName TEXT,
                                suggestedLatitude REAL,
                                suggestedLongitude REAL,
                                FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                                FOREIGN KEY (scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO pending_reviews_new (
                                id, rawNotificationId, scannedReceiptId, suggestedAmount,
                                suggestedCurrency, suggestedMerchant, suggestedMerchantKey,
                                suggestedType, suggestedCategoryId, suggestedDate, confidence,
                                matchType, explanation, packageName, notificationTitle,
                                notificationText, createdAt, status, suggestedDirection,
                                suggestedAccountName, suggestedLatitude, suggestedLongitude
                            )
                            SELECT
                                id, rawNotificationId, scannedReceiptId, suggestedAmount,
                                suggestedCurrency, suggestedMerchant, suggestedMerchantKey,
                                suggestedType, suggestedCategoryId, suggestedDate, confidence,
                                matchType, explanation, packageName, notificationTitle,
                                notificationText, createdAt, status, suggestedDirection,
                                suggestedAccountName, suggestedLatitude, suggestedLongitude
                            FROM pending_reviews
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE pending_reviews")
                        database.execSQL("ALTER TABLE pending_reviews_new RENAME TO pending_reviews")

                        // Recreate pending_reviews indexes
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_suggestedMerchantKey ON pending_reviews (suggestedMerchantKey)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_suggestedMerchantKey_suggestedDate ON pending_reviews (status, suggestedMerchantKey, suggestedDate)")

                        // ── 4. expenses: FK cleanup + rebuild with splitTemplateId FK ───

                        // Orphan cleanup: NULL out splitTemplateId values that reference
                        // non-existent split_templates rows.
                        database.execSQL(
                            """
                            UPDATE expenses
                            SET splitTemplateId = NULL
                            WHERE splitTemplateId IS NOT NULL
                              AND splitTemplateId NOT IN (SELECT id FROM split_templates)
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            CREATE TABLE expenses_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                amount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                merchant TEXT NOT NULL,
                                transactionType TEXT NOT NULL,
                                date INTEGER NOT NULL,
                                rawNotificationId INTEGER,
                                categoryId INTEGER,
                                createdAt INTEGER NOT NULL DEFAULT 0,
                                paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN',
                                isManualEntry INTEGER NOT NULL DEFAULT 0,
                                notes TEXT,
                                dedupeKey TEXT,
                                transferDirection TEXT,
                                transferAccountName TEXT,
                                isNotMine INTEGER NOT NULL DEFAULT 0,
                                ownerName TEXT,
                                isSharedExpense INTEGER NOT NULL DEFAULT 0,
                                sharedWithName TEXT,
                                mySharePercentage INTEGER,
                                myShareAmount REAL,
                                latitude REAL,
                                longitude REAL,
                                locationSource TEXT,
                                placeId TEXT,
                                backfillAttempts INTEGER NOT NULL DEFAULT 0,
                                resolvedAddress TEXT,
                                merchantKey TEXT,
                                isBusinessExpense INTEGER NOT NULL DEFAULT 0,
                                businessPurpose TEXT,
                                businessCategory TEXT,
                                businessProject TEXT,
                                requiresReceipt INTEGER NOT NULL DEFAULT 0,
                                splitTemplateId INTEGER,
                                splitVisualization TEXT,
                                FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL,
                                FOREIGN KEY (splitTemplateId) REFERENCES split_templates(id) ON DELETE SET NULL
                            )
                            """.trimIndent()
                        )
                        // DB-4: Use explicit column list instead of SELECT *
                        database.execSQL(
                            """
                            INSERT INTO expenses_new (
                                id, amount, currency, merchant, transactionType, date,
                                rawNotificationId, categoryId, createdAt, paymentMethod,
                                isManualEntry, notes, dedupeKey, transferDirection,
                                transferAccountName, isNotMine, ownerName, isSharedExpense,
                                sharedWithName, mySharePercentage, myShareAmount, latitude,
                                longitude, locationSource, placeId, backfillAttempts,
                                resolvedAddress, merchantKey, isBusinessExpense,
                                businessPurpose, businessCategory, businessProject,
                                requiresReceipt, splitTemplateId, splitVisualization
                            )
                            SELECT
                                id, amount, currency, merchant, transactionType, date,
                                rawNotificationId, categoryId, createdAt, paymentMethod,
                                isManualEntry, notes, dedupeKey, transferDirection,
                                transferAccountName, isNotMine, ownerName, isSharedExpense,
                                sharedWithName, mySharePercentage, myShareAmount, latitude,
                                longitude, locationSource, placeId, backfillAttempts,
                                resolvedAddress, merchantKey, isBusinessExpense,
                                businessPurpose, businessCategory, businessProject,
                                requiresReceipt, splitTemplateId, splitVisualization
                            FROM expenses
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE expenses")
                        database.execSQL("ALTER TABLE expenses_new RENAME TO expenses")

                        // Recreate all 15 expenses indexes (13 original + backfill + merchantKey_date_amount + splitTemplateId)
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_rawNotificationId ON expenses (rawNotificationId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses (date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_date ON expenses (transactionType, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_categoryId_date ON expenses (transactionType, categoryId, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_categoryId_date ON expenses (categoryId, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_amount_merchant_date ON expenses (amount, merchant, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchant_date ON expenses (merchant, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_transactionType_merchant_date ON expenses (transactionType, merchant, date)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_dedupeKey ON expenses (dedupeKey)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_latitude_longitude ON expenses (latitude, longitude)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_latitude_backfillAttempts_date ON expenses (latitude, backfillAttempts, date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchantKey ON expenses (merchantKey)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_merchantKey_date_amount ON expenses (merchantKey, date, amount)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_isBusinessExpense ON expenses (isBusinessExpense)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_splitTemplateId ON expenses (splitTemplateId)")

                        // ── 5. budgets: data cleanup + rebuild with CHECKs ──────────────

                        // Clamp non-positive amounts
                        database.execSQL("UPDATE budgets SET amount = 0.01 WHERE amount <= 0")

                        // Fix threshold ordering: ensure warning and critical are positive
                        // and warning ≤ critical.
                        database.execSQL("UPDATE budgets SET notifyAtWarning = 0.75 WHERE notifyAtWarning <= 0")
                        database.execSQL("UPDATE budgets SET notifyAtCritical = 0.9 WHERE notifyAtCritical <= 0")
                        // If warning > critical after cleanup, reset both to defaults
                        database.execSQL(
                            """
                            UPDATE budgets
                            SET notifyAtWarning = 0.75, notifyAtCritical = 0.9
                            WHERE notifyAtWarning > notifyAtCritical
                            """.trimIndent()
                        )

                        database.execSQL(
                            """
                            CREATE TABLE budgets_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                categoryId INTEGER,
                                amount REAL NOT NULL CHECK(amount > 0),
                                period TEXT NOT NULL,
                                periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                                startDate INTEGER NOT NULL,
                                isActive INTEGER NOT NULL DEFAULT 1,
                                notifyAtWarning REAL NOT NULL DEFAULT 0.75 CHECK(notifyAtWarning > 0),
                                notifyAtCritical REAL NOT NULL DEFAULT 0.9 CHECK(notifyAtCritical > 0),
                                rollover INTEGER NOT NULL DEFAULT 0,
                                createdAt INTEGER NOT NULL,
                                lastWarningNotifiedAt INTEGER,
                                lastCriticalNotifiedAt INTEGER,
                                lastExceededNotifiedAt INTEGER,
                                CHECK(notifyAtWarning <= notifyAtCritical),
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO budgets_new (
                                id, categoryId, amount, period, periodMode, startDate,
                                isActive, notifyAtWarning, notifyAtCritical, rollover,
                                createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt,
                                lastExceededNotifiedAt
                            )
                            SELECT
                                id, categoryId, amount, period, periodMode, startDate,
                                isActive, notifyAtWarning, notifyAtCritical, rollover,
                                createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt,
                                lastExceededNotifiedAt
                            FROM budgets
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE budgets")
                        database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")

                        // Recreate Room-declared budgets indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")

                        // ── 6. Post-rebuild FK verification ─────────────────────────────
                        database.query("PRAGMA foreign_key_check").use { violations ->
                            if (violations.moveToFirst()) {
                                throw IllegalStateException(
                                    "Migration 75→76 produced FK violations"
                                )
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        /**
         * Callback that creates supplementary partial unique indexes and CHECK
         * constraints on **fresh install** for tables where Room annotations
         * cannot express them.
         *
         * Room's `@Index` annotation does not support `WHERE` clauses, and there
         * is no `@Check` annotation, so these must be applied via raw SQL after
         * Room creates the schema.  Tables are rebuilt (CREATE-new, INSERT,
         * DROP-old, RENAME) to apply CHECK constraints.
         *
         * On upgrade paths the same indexes/constraints are created by the
         * respective migrations ([MIGRATION_70_71], [MIGRATION_74_75],
         * [MIGRATION_75_76], [MIGRATION_104_105], [MIGRATION_106_107]).
         *
         * ## E5: Index name parity with entity declarations
         * The materialized-key unique indexes in Phase 7 (MIGRATION_104_105)
         * originally used the `idx_` prefix.  MIGRATION_106_107 corrected these
         * to `index_` to match Room's auto-naming convention.  However, the
         * FRESH_INSTALL_CALLBACK below still uses the old `idx_` prefix for:
         *   - `index_budgets_activeOverallKey`         (matches entity)
         *   - `index_budgets_activeCategoryKey`        (matches entity)
         *   - `index_group_members_currentUserGroupKey` (matches entity)
         *   - `index_planned_expenses_openSourceOccurrenceKey` (matches entity)
         *
         * These `CREATE UNIQUE INDEX IF NOT EXISTS` statements are idempotent
         * and functionally correct (the prefix difference does not affect
         * correctness).  A future cleanup should rename them to `index_` for
         * consistency.  On fresh installs both the `idx_` and the Room-generated
         * `index_` forms would coexist, which is harmless since they reference
         * different columns.  On upgrades from v104 the migration chain
         * produces the `index_` forms correctly.
         *
         * ## MIG-1: Fresh-install callback parity
         * Index name parity between FRESH_INSTALL_CALLBACK and entity
         * declarations was documented in Batch E (see E5 above). The four
         * `index_`-prefixed indexes in the callback now match Room entity conventions.
         * as-is because:
         *  - They are backed by `CREATE UNIQUE INDEX IF NOT EXISTS`, which is
         *    idempotent — both `idx_` and `index_` forms can coexist harmlessly.
         *  - Fresh-install paths are tested by
         *    [FreshInstallIndexParityTest] and [FreshInstallBatch8ParityTest],
         *    which verify the callback fires and produces correct schemas.
         *  - Room's auto-migration from v104 produces the `index_` forms
         *    correctly, so upgrade paths are not affected.
         *
         * ## Invariants enforced
         *  - At most one raw_notification per (packageName, timestamp) combo per NULL pattern.
         *  - savings_goals: targetAmount > 0, currentAmount >= 0.
         *  - mileage_tracking: distanceKm > 0, deductionRatePerKm >= 0, fuelCost >= 0,
         *    odometer ordering.
         *  - pending_reviews: suggestedAmount > 0, suggestedType ∈ known enum set.
         *  - budgets: amount > 0, notifyAtWarning > 0, notifyAtCritical > 0,
         *    notifyAtWarning ≤ notifyAtCritical,
         *    materialized-key invariant (activeOverallKey / activeCategoryKey).
         *  - group_members: currentUserGroupKey invariant.
         *  - planned_expenses: openSourceOccurrenceKey invariant.
         */
        val FRESH_INSTALL_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onCreate(db)
                // B4 Batch 6 removed — partial unique indexes were moved to MIGRATION_144_145 cleanup
                // ── B8: CHECK constraints (table rebuilds — tables are empty on fresh install) ──

                // savings_goals: targetAmount > 0, currentAmount >= 0
                db.execSQL(
                    """
                    CREATE TABLE savings_goals_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        targetAmount REAL NOT NULL CHECK(targetAmount > 0),
                        currentAmount REAL NOT NULL DEFAULT 0.0 CHECK(currentAmount >= 0),
                        targetDate INTEGER,
                        protectionLevel TEXT NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO savings_goals_new (
                        id, name, targetAmount, currentAmount, targetDate,
                        protectionLevel, currency, currencyAssumption, createdAt
                    )
                    SELECT
                        id, name, targetAmount, currentAmount, targetDate,
                        protectionLevel, currency, currencyAssumption, createdAt
                    FROM savings_goals
                """.trimIndent())
                db.execSQL("DROP TABLE savings_goals")
                db.execSQL("ALTER TABLE savings_goals_new RENAME TO savings_goals")

                // mileage_tracking: distanceKm > 0, deductionRatePerKm >= 0,
                // fuelCost >= 0 when non-null, odometer ordering
                db.execSQL(
                    """
                    CREATE TABLE mileage_tracking_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date INTEGER NOT NULL,
                        startOdometer REAL,
                        endOdometer REAL,
                        distanceKm REAL NOT NULL CHECK(distanceKm > 0),
                        startLocation TEXT,
                        endLocation TEXT,
                        startLatitude REAL,
                        startLongitude REAL,
                        endLatitude REAL,
                        endLongitude REAL,
                        isBusinessTrip INTEGER NOT NULL,
                        tripPurpose TEXT NOT NULL,
                        businessProject TEXT,
                        clientName TEXT,
                        deductionRatePerKm REAL NOT NULL CHECK(deductionRatePerKm >= 0),
                        calculatedDeduction REAL,
                        linkedExpenseId INTEGER,
                        fuelCost REAL CHECK(fuelCost IS NULL OR fuelCost >= 0),
                        notes TEXT,
                        createdAt INTEGER NOT NULL,
                        CHECK(endOdometer IS NULL OR startOdometer IS NULL OR endOdometer >= startOdometer),
                        FOREIGN KEY(linkedExpenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO mileage_tracking_new (
                        id, date, startOdometer, endOdometer, distanceKm,
                        startLocation, endLocation, startLatitude, startLongitude,
                        endLatitude, endLongitude, isBusinessTrip, tripPurpose,
                        businessProject, clientName, deductionRatePerKm,
                        calculatedDeduction, linkedExpenseId, fuelCost, notes, createdAt
                    )
                    SELECT
                        id, date, startOdometer, endOdometer, distanceKm,
                        startLocation, endLocation, startLatitude, startLongitude,
                        endLatitude, endLongitude, isBusinessTrip, tripPurpose,
                        businessProject, clientName, deductionRatePerKm,
                        calculatedDeduction, linkedExpenseId, fuelCost, notes, createdAt
                    FROM mileage_tracking
                """.trimIndent())
                db.execSQL("DROP TABLE mileage_tracking")
                db.execSQL("ALTER TABLE mileage_tracking_new RENAME TO mileage_tracking")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_linkedExpenseId ON mileage_tracking (linkedExpenseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_date ON mileage_tracking (date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mileage_tracking_isBusinessTrip ON mileage_tracking (isBusinessTrip)")

                // pending_reviews: suggestedAmount > 0, suggestedType enum guard
                db.execSQL(
                    """
                    CREATE TABLE pending_reviews_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawNotificationId INTEGER,
                        scannedReceiptId INTEGER,
                        suggestedAmount REAL NOT NULL CHECK(suggestedAmount > 0),
                        suggestedCurrency TEXT NOT NULL,
                        suggestedMerchant TEXT NOT NULL,
                        suggestedMerchantKey TEXT,
                        suggestedType TEXT NOT NULL CHECK(suggestedType IN ('PURCHASE', 'WITHDRAWAL', 'TRANSFER', 'DEPOSIT', 'UNKNOWN')),
                        suggestedCategoryId INTEGER,
                        suggestedDate INTEGER,
                        confidence REAL NOT NULL,
                        matchType TEXT,
                        explanation TEXT,
                        packageName TEXT NOT NULL,
                        notificationTitle TEXT,
                        notificationText TEXT,
                        createdAt INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        suggestedDirection TEXT,
                        suggestedAccountName TEXT,
                        suggestedLatitude REAL,
                        suggestedLongitude REAL,
                        extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION',
                        FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                        FOREIGN KEY (scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO pending_reviews_new (
                        id, rawNotificationId, scannedReceiptId,
                        suggestedAmount, suggestedCurrency, suggestedMerchant,
                        suggestedMerchantKey, suggestedType, suggestedCategoryId,
                        suggestedDate, confidence, matchType, explanation,
                        packageName, notificationTitle, notificationText,
                        createdAt, status, suggestedDirection,
                        suggestedAccountName, suggestedLatitude, suggestedLongitude,
                        extractionState
                    )
                    SELECT
                        id, rawNotificationId, scannedReceiptId,
                        suggestedAmount, suggestedCurrency, suggestedMerchant,
                        suggestedMerchantKey, suggestedType, suggestedCategoryId,
                        suggestedDate, confidence, matchType, explanation,
                        packageName, notificationTitle, notificationText,
                        createdAt, status, suggestedDirection,
                        suggestedAccountName, suggestedLatitude, suggestedLongitude,
                        COALESCE(extractionState, 'REAL_EXTRACTION')
                    FROM pending_reviews
                """.trimIndent())
                db.execSQL("DROP TABLE pending_reviews")
                db.execSQL("ALTER TABLE pending_reviews_new RENAME TO pending_reviews")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_suggestedMerchantKey ON pending_reviews (suggestedMerchantKey)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_suggestedMerchantKey_suggestedDate ON pending_reviews (status, suggestedMerchantKey, suggestedDate)")

                // budgets: amount > 0, notifyAtWarning > 0, notifyAtCritical > 0,
                // notifyAtWarning <= notifyAtCritical,
                // materialized key invariant (activeOverallKey / activeCategoryKey)
                db.execSQL(
                    """
                    CREATE TABLE budgets_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER,
                        amount REAL NOT NULL CHECK(amount > 0),
                        period TEXT NOT NULL,
                        periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                        startDate INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        notifyAtWarning REAL NOT NULL DEFAULT 0.75 CHECK(notifyAtWarning > 0),
                        notifyAtCritical REAL NOT NULL DEFAULT 0.9 CHECK(notifyAtCritical > 0),
                        rollover INTEGER NOT NULL DEFAULT 0,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                        createdAt INTEGER NOT NULL,
                        lastWarningNotifiedAt INTEGER,
                        lastCriticalNotifiedAt INTEGER,
                        lastExceededNotifiedAt INTEGER,
                        activeOverallKey INTEGER,
                        activeCategoryKey INTEGER,
                        CHECK(notifyAtWarning <= notifyAtCritical),
                        CHECK(
                            (isActive = 0 AND activeOverallKey IS NULL AND activeCategoryKey IS NULL)
                            OR
                            (isActive = 1 AND categoryId IS NULL AND activeOverallKey = 1 AND activeCategoryKey IS NULL)
                            OR
                            (isActive = 1 AND categoryId IS NOT NULL AND activeOverallKey IS NULL AND activeCategoryKey = categoryId)
                        ),
                        FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                    )
                """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO budgets_new (
                        id, categoryId, amount, period, periodMode, startDate,
                        isActive, notifyAtWarning, notifyAtCritical, rollover,
                        currency, currencyAssumption, createdAt,
                        lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt,
                        activeOverallKey, activeCategoryKey
                    )
                    SELECT
                        id, categoryId, amount, period, periodMode, startDate,
                        isActive, notifyAtWarning, notifyAtCritical, rollover,
                        currency, currencyAssumption, createdAt,
                        lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt,
                        activeOverallKey, activeCategoryKey
                    FROM budgets
                """.trimIndent())
                db.execSQL("DROP TABLE budgets")
                db.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey)")

                // ── Phase 7: materialized key CHECK constraints ───────────────────────────

                // group_members: currentUserGroupKey invariant
                db.execSQL(
                    """
                    CREATE TABLE group_members_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        email TEXT,
                        isCurrentUser INTEGER NOT NULL DEFAULT 0,
                        joinedAt INTEGER NOT NULL,
                        currentUserGroupKey INTEGER,
                        CHECK(
                            (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
                            OR
                            (isCurrentUser = 1 AND currentUserGroupKey IS NOT NULL AND currentUserGroupKey = groupId)
                        ),
                        FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO group_members_new (
                        id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey
                    )
                    SELECT
                        id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey
                    FROM group_members
                """.trimIndent())
                db.execSQL("DROP TABLE group_members")
                db.execSQL("ALTER TABLE group_members_new RENAME TO group_members")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_isCurrentUser ON group_members (groupId, isCurrentUser)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey)")

                // planned_expenses: openSourceOccurrenceKey invariant
                db.execSQL(
                    """
                    CREATE TABLE planned_expenses_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        description TEXT NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                        date INTEGER NOT NULL,
                        categoryId INTEGER,
                        isRecurring INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        sourceOccurrenceKey TEXT,
                        sourceRecurringRuleId INTEGER,
                        status TEXT NOT NULL DEFAULT 'PLANNED',
                        linkedActualExpenseId INTEGER,
                        merchantKey TEXT,
                        updatedAt INTEGER NOT NULL,
                        openSourceOccurrenceKey TEXT,
                        CHECK(
                            (status != 'PLANNED' AND openSourceOccurrenceKey IS NULL)
                            OR
                            (status = 'PLANNED' AND sourceOccurrenceKey IS NULL AND openSourceOccurrenceKey IS NULL)
                            OR
                            (status = 'PLANNED' AND sourceOccurrenceKey IS NOT NULL AND openSourceOccurrenceKey = sourceOccurrenceKey)
                        ),
                        FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("""
                    INSERT INTO planned_expenses_new (
                        id, description, amount, currency, currencyAssumption,
                        date, categoryId, isRecurring, priority, createdAt,
                        sourceOccurrenceKey, sourceRecurringRuleId, status,
                        linkedActualExpenseId, merchantKey, updatedAt,
                        openSourceOccurrenceKey
                    )
                    SELECT
                        id, description, amount, currency, currencyAssumption,
                        date, categoryId, isRecurring, priority, createdAt,
                        sourceOccurrenceKey, sourceRecurringRuleId, status,
                        linkedActualExpenseId, merchantKey, updatedAt,
                        openSourceOccurrenceKey
                    FROM planned_expenses
                """.trimIndent())
                db.execSQL("DROP TABLE planned_expenses")
                db.execSQL("ALTER TABLE planned_expenses_new RENAME TO planned_expenses")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_expenses_openSourceOccurrenceKey ON planned_expenses (openSourceOccurrenceKey)")
                // ── Case-insensitive unique index on categories ──
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name_nocase ON categories(name COLLATE NOCASE)")
            }
        }

        // Migration 76 → 77: Add missing index on user_corrections.originalMerchant.
        // The entity declared indices for originalCategoryId, correctedCategoryId,
        // packageName, wasApproved, and wasRejected but omitted originalMerchant.
        // This index is needed for efficient merchant-based correction lookups.
        val MIGRATION_76_77 = object : androidx.room.migration.Migration(76, 77) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_user_corrections_originalMerchant " +
                        "ON user_corrections (originalMerchant)"
                )
            }
        }

        val MIGRATION_77_78 = object : androidx.room.migration.Migration(77, 78) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_anomaly_alerts_category_alertedAt " +
                        "ON anomaly_alerts (category, alertedAt)"
                )
            }
        }

        val MIGRATION_78_79 = object : androidx.room.migration.Migration(78, 79) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_exchange_rates_toCurrency " +
                        "ON exchange_rates (toCurrency)"
                )
            }
        }

        val MIGRATION_79_80 = object : androidx.room.migration.Migration(79, 80) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS spending_challenges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        startDate INTEGER NOT NULL,
                        endDate INTEGER NOT NULL,
                        targetAmount REAL,
                        categoryId INTEGER,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        baselineAmount REAL,
                        baselineStartDate INTEGER,
                        baselineEndDate INTEGER,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_spending_challenges_categoryId ON spending_challenges (categoryId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_spending_challenges_isActive ON spending_challenges (isActive)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_spending_challenges_endDate ON spending_challenges (endDate)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_spending_challenges_isActive_endDate ON spending_challenges (isActive, endDate)"
                )
            }
        }

        val MIGRATION_80_81 = object : androidx.room.migration.Migration(80, 81) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "DROP INDEX IF EXISTS index_return_windows_expenseId"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_return_windows_expenseId ON return_windows (expenseId)"
                )
            }
        }

        val MIGRATION_81_82 = object : androidx.room.migration.Migration(81, 82) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    DELETE FROM pending_reviews
                    WHERE rawNotificationId IS NOT NULL
                      AND id NOT IN (
                          SELECT MAX(id)
                          FROM pending_reviews
                          WHERE rawNotificationId IS NOT NULL
                          GROUP BY rawNotificationId
                      )
                    """.trimIndent()
                )
                database.execSQL(
                    "DROP INDEX IF EXISTS index_pending_reviews_rawNotificationId"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)"
                )
            }
        }

        val MIGRATION_82_83 = object : androidx.room.migration.Migration(82, 83) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("UPDATE expenses SET ownerName = NULL WHERE isNotMine = 0")
                database.execSQL(
                    """
                    UPDATE expenses
                    SET isSharedExpense = 0,
                        sharedWithName = NULL,
                        mySharePercentage = NULL,
                        myShareAmount = NULL
                    WHERE isNotMine = 1 AND isSharedExpense = 1
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE expenses
                    SET sharedWithName = NULL,
                        mySharePercentage = NULL,
                        myShareAmount = NULL
                    WHERE isSharedExpense = 0
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_83_84 = object : androidx.room.migration.Migration(83, 84) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // Drop legacy unique index if it survived from pre-73
                        database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_packageName_timestamp_title_text")

                        // Dedup rows with NULL title AND NULL text that share the same
                        // packageName+timestamp. Keep the row with the smallest id.
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE title IS NULL AND text IS NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE title IS NULL AND text IS NULL
                                  GROUP BY packageName, timestamp
                              )
                            """.trimIndent()
                        )

                        // Also dedup rows with NULL title (text NOT NULL).
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE title IS NULL AND text IS NOT NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE title IS NULL AND text IS NOT NULL
                                  GROUP BY packageName, timestamp, text
                              )
                            """.trimIndent()
                        )

                        // Dedup rows with NULL text (title NOT NULL).
                        database.execSQL(
                            """
                            DELETE FROM raw_notifications
                            WHERE text IS NULL AND title IS NOT NULL
                              AND id NOT IN (
                                  SELECT MIN(id)
                                  FROM raw_notifications
                                  WHERE text IS NULL AND title IS NOT NULL
                                  GROUP BY packageName, timestamp, title
                              )
                            """.trimIndent()
                        )

                        // Recreate as non-unique (matches current entity expectation)
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text ON raw_notifications (packageName, timestamp, title, text)")

                        database.execSQL("ALTER TABLE exchange_rates RENAME TO exchange_rates_old")
                        database.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS exchange_rates (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                fromCurrency TEXT NOT NULL,
                                toCurrency TEXT NOT NULL,
                                rate REAL NOT NULL,
                                lastUpdated INTEGER NOT NULL,
                                source TEXT NOT NULL DEFAULT 'manual'
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO exchange_rates (id, fromCurrency, toCurrency, rate, lastUpdated, source)
                            SELECT id, fromCurrency, toCurrency, rate, lastUpdated, source
                            FROM exchange_rates_old
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE exchange_rates_old")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_exchange_rates_fromCurrency_toCurrency ON exchange_rates (fromCurrency, toCurrency)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_exchange_rates_lastUpdated ON exchange_rates (lastUpdated)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_exchange_rates_toCurrency ON exchange_rates (toCurrency)")

                        database.execSQL("ALTER TABLE budget_forecasts RENAME TO budget_forecasts_old")
                        database.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS budget_forecasts (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                budgetId INTEGER NOT NULL,
                                forecastDate INTEGER NOT NULL,
                                targetPeriodStart INTEGER NOT NULL,
                                targetPeriodEnd INTEGER NOT NULL,
                                predictedSpending REAL NOT NULL,
                                predictedRemaining REAL NOT NULL,
                                confidenceScore REAL NOT NULL,
                                riskLevel TEXT NOT NULL,
                                overspendProbability REAL NOT NULL,
                                recommendationsJson TEXT,
                                actualSpending REAL,
                                forecastAccuracy REAL,
                                isActive INTEGER NOT NULL DEFAULT 1,
                                createdAt INTEGER NOT NULL,
                                FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO budget_forecasts (
                                id,
                                budgetId,
                                forecastDate,
                                targetPeriodStart,
                                targetPeriodEnd,
                                predictedSpending,
                                predictedRemaining,
                                confidenceScore,
                                riskLevel,
                                overspendProbability,
                                recommendationsJson,
                                actualSpending,
                                forecastAccuracy,
                                isActive,
                                createdAt
                            )
                            SELECT
                                id,
                                budgetId,
                                forecastDate,
                                targetPeriodStart,
                                targetPeriodEnd,
                                predictedSpending,
                                predictedRemaining,
                                confidenceScore,
                                riskLevel,
                                overspendProbability,
                                recommendationsJson,
                                actualSpending,
                                forecastAccuracy,
                                isActive,
                                createdAt
                            FROM budget_forecasts_old
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE budget_forecasts_old")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId ON budget_forecasts (budgetId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate ON budget_forecasts (forecastDate)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive ON budget_forecasts (isActive)")

                        database.execSQL("ALTER TABLE subscription_price_history RENAME TO subscription_price_history_old")
                        database.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS subscription_price_history (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                subscriptionId INTEGER NOT NULL,
                                amount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                recordedAt INTEGER NOT NULL,
                                changeReason TEXT,
                                FOREIGN KEY(subscriptionId) REFERENCES manual_recurring_expenses(id) ON DELETE CASCADE
                            )
                            """.trimIndent()
                        )
                        database.execSQL(
                            """
                            INSERT INTO subscription_price_history (id, subscriptionId, amount, currency, recordedAt, changeReason)
                            SELECT id, subscriptionId, amount, currency, recordedAt, changeReason
                            FROM subscription_price_history_old
                            """.trimIndent()
                        )
                        database.execSQL("DROP TABLE subscription_price_history_old")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_price_history_subscriptionId_recordedAt ON subscription_price_history (subscriptionId, recordedAt)")

                        // Ensure unique index on rawNotificationId (normally added in 81→82, but heal if missed)
                        database.execSQL("DELETE FROM pending_reviews WHERE rawNotificationId IS NOT NULL AND id NOT IN (SELECT MAX(id) FROM pending_reviews WHERE rawNotificationId IS NOT NULL GROUP BY rawNotificationId)")
                        database.execSQL("DROP INDEX IF EXISTS index_pending_reviews_rawNotificationId")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        val MIGRATION_84_85 = object : androidx.room.migration.Migration(84, 85) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // Drop non-entity partial dedup indexes if they exist.
                    database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_nonnull")
                    database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_both_null")
                    database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_title_null")
                    database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_text_null")

                    // Recreate covering index as non-unique to match Room contract.
                    database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_packageName_timestamp_title_text")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text ON raw_notifications (packageName, timestamp, title, text)")

                    // Ensure expected Room-declared indexes exist.
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp ON raw_notifications (packageName, timestamp)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_capturedAt ON raw_notifications (capturedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_isRelevant ON raw_notifications (isRelevant)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 85 -> 86: rebuild budgets to restore Room-expected defaults.
        val MIGRATION_85_86 = object : androidx.room.migration.Migration(85, 86) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL(
                        """
                        CREATE TABLE budgets_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            categoryId INTEGER,
                            amount REAL NOT NULL,
                            period TEXT NOT NULL,
                            periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                            startDate INTEGER NOT NULL,
                            isActive INTEGER NOT NULL DEFAULT 1,
                            notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                            notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                            rollover INTEGER NOT NULL DEFAULT 0,
                            createdAt INTEGER NOT NULL,
                            lastWarningNotifiedAt INTEGER,
                            lastCriticalNotifiedAt INTEGER,
                            lastExceededNotifiedAt INTEGER,
                            FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        INSERT INTO budgets_new (
                            id,
                            categoryId,
                            amount,
                            period,
                            periodMode,
                            startDate,
                            isActive,
                            notifyAtWarning,
                            notifyAtCritical,
                            rollover,
                            createdAt,
                            lastWarningNotifiedAt,
                            lastCriticalNotifiedAt,
                            lastExceededNotifiedAt
                        )
                        SELECT
                            id,
                            categoryId,
                            amount,
                            period,
                            periodMode,
                            startDate,
                            isActive,
                            notifyAtWarning,
                            notifyAtCritical,
                            rollover,
                            createdAt,
                            lastWarningNotifiedAt,
                            lastCriticalNotifiedAt,
                            lastExceededNotifiedAt
                        FROM budgets
                        """.trimIndent()
                    )

                    database.execSQL("DROP TABLE budgets")
                    database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")

                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 86 -> 87: remove non-entity budgets partial unique indexes
        // so runtime schema exactly matches Room's expected budgets index set.
        val MIGRATION_86_87 = object : androidx.room.migration.Migration(86, 87) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_budgets_active_overall")
                    database.execSQL("DROP INDEX IF EXISTS index_budgets_active_category")
                    database.execSQL("DROP INDEX IF EXISTS index_budgets_categoryId")
                    database.execSQL("DROP INDEX IF EXISTS index_budgets_isActive")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 87 -> 88: remove non-Room group_members partial index
        // so runtime schema exactly matches Room's expected index set.
        val MIGRATION_87_88 = object : androidx.room.migration.Migration(87, 88) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_group_members_groupId_currentUser")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_isCurrentUser ON group_members (groupId, isCurrentUser)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 88 -> 89: remove non-Room group_expenses partial index
        // so runtime schema exactly matches Room's expected index set.
        val MIGRATION_88_89 = object : androidx.room.migration.Migration(88, 89) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_group_expenses_expenseId_unique")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId ON group_expenses (groupId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_paidById ON group_expenses (paidById)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_groupId_date ON group_expenses (groupId, date)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_group_expenses_isReimbursable ON group_expenses (isReimbursable)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 89 -> 90: remove non-Room budget_forecasts partial index
        // so runtime schema exactly matches Room's expected index set.
        val MIGRATION_89_90 = object : androidx.room.migration.Migration(89, 90) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_budget_forecasts_active_budget_period")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId ON budget_forecasts (budgetId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate ON budget_forecasts (forecastDate)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive ON budget_forecasts (isActive)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 90 -> 91: remove non-Room subscription_candidates partial index
        // so runtime schema exactly matches Room's expected index set.
        val MIGRATION_90_91 = object : androidx.room.migration.Migration(90, 91) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL("DROP INDEX IF EXISTS index_subscription_candidates_pending_merchant_interval")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_canonicalMerchant ON subscription_candidates (canonicalMerchant)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_isConverted ON subscription_candidates (isConverted)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_candidates_confidence ON subscription_candidates (confidence)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Healing migration 91 -> 92: rebuild email_receipt_sources so legacy
        // long-hop/import paths converge on Room's exact column default contract.
        val MIGRATION_91_92 = object : androidx.room.migration.Migration(91, 92) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    database.execSQL(
                        """
                        CREATE TABLE email_receipt_sources_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            emailSender TEXT NOT NULL,
                            emailSubject TEXT NOT NULL,
                            emailMessageId TEXT DEFAULT NULL,
                            parsedAt INTEGER NOT NULL,
                            provider TEXT NOT NULL,
                            confidence REAL NOT NULL,
                            fingerprint TEXT NOT NULL DEFAULT '',
                            FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    database.execSQL(
                        """
                        INSERT INTO email_receipt_sources_new (
                            id, receiptId, emailSender, emailSubject, emailMessageId,
                            parsedAt, provider, confidence, fingerprint
                        )
                        SELECT
                            id,
                            receiptId,
                            emailSender,
                            emailSubject,
                            NULLIF(TRIM(emailMessageId), ''),
                            parsedAt,
                            provider,
                            confidence,
                            fingerprint
                        FROM email_receipt_sources
                        """.trimIndent()
                    )

                    database.execSQL("DROP TABLE email_receipt_sources")
                    database.execSQL("ALTER TABLE email_receipt_sources_new RENAME TO email_receipt_sources")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources(receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources(provider, parsedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources(parsedAt)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources(fingerprint)")
                    database.execSQL("DROP INDEX IF EXISTS index_email_receipt_sources_emailMessageId")
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources(emailMessageId) WHERE emailMessageId IS NOT NULL"
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
            }
        }

        val MIGRATION_144_145 = object : androidx.room.migration.Migration(144, 145) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                var hasRolloverDeficitTracking = false
                database.query("PRAGMA table_info(`budgets`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(nameIndex) == "rolloverDeficitTracking") {
                            hasRolloverDeficitTracking = true
                            break
                        }
                    }
                }
                if (!hasRolloverDeficitTracking) {
                    database.execSQL("ALTER TABLE budgets ADD COLUMN rolloverDeficitTracking INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
    }

val MIGRATION_92_93 = object : androidx.room.migration.Migration(92, 93) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("DROP INDEX IF EXISTS `index_raw_notifications_packageName_timestamp_title_text`")
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_notifications_packageName_timestamp_title_text_bigText` ON `raw_notifications` (`packageName`, `timestamp`, `title`, `text`, `bigText`)")
    }
}

val MIGRATION_93_94 = object : androidx.room.migration.Migration(93, 94) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        // CURRENCY-FOUNDATION: Add currency tracking to budgets table.
        // Existing budgets get EUR with LEGACY_DEFAULT assumption,
        // indicating the currency was not originally stored.
        database.execSQL("ALTER TABLE budgets ADD COLUMN currency TEXT NOT NULL DEFAULT 'EUR'")
        database.execSQL("ALTER TABLE budgets ADD COLUMN currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT'")
    }
}

// Migration 94 -> 95: Transaction Lifecycle Foundation (Phase 3, PR 1).
//
// 1. Add nullable source column to expenses for tracking the origin of each expense.
//    Nullable for backward compatibility with legacy rows; backfilled by migration.
// 2. Create transaction_events table for recording expense lifecycle events.
val MIGRATION_94_95 = object : androidx.room.migration.Migration(94, 95) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT")

        database.execSQL("""
            CREATE TABLE IF NOT EXISTS transaction_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                expenseId INTEGER,
                eventType TEXT NOT NULL,
                source TEXT NOT NULL,
                actor TEXT,
                occurredAt INTEGER NOT NULL,
                dedupeKey TEXT,
                duplicateExpenseId INTEGER,
                beforeSnapshot TEXT,
                afterSnapshot TEXT,
                metadata TEXT,
                reason TEXT
            )
        """.trimIndent())

        database.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_events_expenseId ON transaction_events (expenseId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_events_source ON transaction_events (source)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_events_occurredAt ON transaction_events (occurredAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_events_eventType ON transaction_events (eventType)")
    }
}

// Migration 95 -> 96: Receipt Lifecycle Foundation (Phase 4, PR 1).
//
// 1. Add new columns to scanned_receipts for receipt lifecycle tracking:
//    sourceType, documentType, processingStatus, sourceFingerprint, imageHash,
//    textFingerprint, semanticFingerprint, ocrConfidence, parseFailureReason, updatedAt.
// 2. Create receipt_events table for recording receipt lifecycle events.
// 3. Create receipt_expense_links table for linking receipts to expenses.
// 4. Backfill sourceType/documentType/processingStatus based on heuristics.
/**
 * Migration 95 → 96: Receipt lifecycle tables (Phase 4).
 *
 * FK note: The Room entities for [ReceiptEvent], [ReceiptExpenseLink],
 * [RecurringOccurrence], and [RecurringReminderDelivery] do NOT declare
 * foreign key annotations. The CREATE TABLE statements below intentionally
 * omit FOREIGN KEY clauses to match the entity definitions. Room schema
 * validation passes because the exported schema JSON (derived from Room
 * annotations) matches the migration-created schema.
 *
 * If foreign keys are added to these entities in the future, the
 * corresponding CREATE TABLE SQL in this migration MUST be updated to
 * include them; otherwise Room's compile-time schema verification will fail.
 */
val MIGRATION_95_96 = object : androidx.room.migration.Migration(95, 96) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.beginTransaction()
        try {
            // 1. Add new columns to scanned_receipts
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT")
            database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

            // Add index on processingStatus
            database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_processingStatus ON scanned_receipts (processingStatus)")

            // 2. Create receipt_events table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS receipt_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    receiptId INTEGER,
                    sourceType TEXT NOT NULL,
                    documentType TEXT NOT NULL,
                    eventType TEXT NOT NULL,
                    occurredAt INTEGER NOT NULL,
                    oldStatus TEXT,
                    newStatus TEXT,
                    actor TEXT,
                    message TEXT,
                    metadata TEXT,
                    errorDetails TEXT
                )
            """.trimIndent())

            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_events_receiptId ON receipt_events (receiptId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_events_sourceType ON receipt_events (sourceType)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_events_documentType ON receipt_events (documentType)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_events_occurredAt ON receipt_events (occurredAt)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_events_eventType ON receipt_events (eventType)")

            // 3. Create receipt_expense_links table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS receipt_expense_links (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    receiptId INTEGER NOT NULL,
                    expenseId INTEGER NOT NULL,
                    linkType TEXT NOT NULL,
                    confidence REAL,
                    source TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    createdBy TEXT,
                    isPrimary INTEGER NOT NULL DEFAULT 1,
                    metadata TEXT
                )
            """.trimIndent())

            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_receiptId ON receipt_expense_links (receiptId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_expenseId ON receipt_expense_links (expenseId)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_receipt_expense_links_receiptId_expenseId ON receipt_expense_links (receiptId, expenseId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_linkType ON receipt_expense_links (linkType)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_createdAt ON receipt_expense_links (createdAt)")

            // 4. Backfill heuristics
            // Backfill sourceType/documentType based on email receipt sources
            database.execSQL("""
                UPDATE scanned_receipts SET sourceType = 'EMAIL', documentType = 'EMAIL_RECEIPT'
                WHERE id IN (SELECT receiptId FROM email_receipt_sources)
            """.trimIndent())

            // Backfill documentType for bank statements based on merchant name
            database.execSQL("""
                UPDATE scanned_receipts SET documentType = 'BANK_STATEMENT'
                WHERE parsedMerchant LIKE '%Bank Statement%'
            """.trimIndent())

            // Backfill documentType and processingStatus for failed scans
            database.execSQL("""
                UPDATE scanned_receipts SET documentType = 'MANUAL_PLACEHOLDER', processingStatus = 'OCR_FAILED'
                WHERE rawOcrText LIKE 'Scan Failed%' OR rawOcrText LIKE '[OCR Failed%'
            """.trimIndent())

            // Backfill processingStatus for already-parsed receipts
            database.execSQL("""
                UPDATE scanned_receipts SET processingStatus = 'PARSED'
                WHERE parsedMerchant IS NOT NULL
            """.trimIndent())

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}

/**
 * Migration 96 → 100: Recurring Occurrences and Reminder Deliveries (Phase 5, PR B).
 *
 * 1. CREATE TABLE recurring_occurrences with all columns + indices.
 * 2. CREATE TABLE recurring_reminder_deliveries with all columns + indices.
 * 3. Add sourceOccurrenceKey TEXT column to planned_expenses table.
 * 4. Add sourceRecurringRuleId INTEGER column to planned_expenses table.
 *
 * FK note: The Room entities for [RecurringOccurrence] and
 * [RecurringReminderDelivery] do NOT declare foreign key annotations
 * (neither does [ReceiptEvent] or [ReceiptExpenseLink] from MIGRATION_95_96).
 * The CREATE TABLE statements below intentionally omit FOREIGN KEY clauses
 * to match the entity definitions. Room schema validation passes because
 * the exported schema JSON matches the migration-created schema.
 */
val MIGRATION_96_100 = object : androidx.room.migration.Migration(96, 100) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.beginTransaction()
        try {
            // 1. Create recurring_occurrences table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS recurring_occurrences (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    sourceType TEXT NOT NULL,
                    sourceId INTEGER NOT NULL,
                    occurrenceKey TEXT NOT NULL,
                    dueDate INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    linkedExpenseId INTEGER,
                    expectedAmount REAL NOT NULL,
                    expectedCurrency TEXT NOT NULL,
                    paidAt INTEGER,
                    paidAmount REAL,
                    paidCurrency TEXT,
                    frequency TEXT NOT NULL,
                    merchant TEXT,
                    categoryId INTEGER,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_occurrences_sourceType_sourceId ON recurring_occurrences (sourceType, sourceId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_occurrences_dueDate ON recurring_occurrences (dueDate)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_occurrences_status ON recurring_occurrences (status)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_occurrences_occurrenceKey ON recurring_occurrences (occurrenceKey)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_occurrences_linkedExpenseId ON recurring_occurrences (linkedExpenseId)")

            // 2. Create recurring_reminder_deliveries table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS recurring_reminder_deliveries (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    occurrenceId INTEGER NOT NULL,
                    reminderWindow TEXT NOT NULL,
                    scheduledAt INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    lastSentAt INTEGER,
                    dismissedAt INTEGER,
                    snoozedUntil INTEGER,
                    notificationId INTEGER,
                    createdAt INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())

            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow ON recurring_reminder_deliveries (occurrenceId, reminderWindow)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_status ON recurring_reminder_deliveries (status)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_scheduledAt ON recurring_reminder_deliveries (scheduledAt)")

            // 3. Add sourceOccurrenceKey column to planned_expenses
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT")

            // 4. Add sourceRecurringRuleId column to planned_expenses
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER")

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}

/**
 * Migration 100 -> 101: Add planned_expenses columns and deduplicate reminder deliveries.
 *
 * 1. Add new planned_expenses columns (status, linkedActualExpenseId, merchantKey, updatedAt)
 *    with safe defaults.
 * 2. Deduplicate recurring_reminder_deliveries (keep earliest per occurrenceId+reminderWindow).
 * 3. Replace non-unique index with UNIQUE index on (occurrenceId, reminderWindow).
 */
val MIGRATION_100_101 = object : androidx.room.migration.Migration(100, 101) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.beginTransaction()
        try {
            // 1. Add new planned_expenses columns (with safe defaults)
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'")
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER")
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT")
            database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

            // 2. Deduplicate reminder deliveries (keep earliest)
            database.execSQL("""
                DELETE FROM recurring_reminder_deliveries
                WHERE id NOT IN (
                    SELECT MIN(id) FROM recurring_reminder_deliveries
                    GROUP BY occurrenceId, reminderWindow
                )
            """.trimIndent())

            // 3. Replace non-unique index with unique
            database.execSQL("DROP INDEX IF EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow")
            database.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow
                ON recurring_reminder_deliveries (occurrenceId, reminderWindow)
            """.trimIndent())

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}

// Migration 101 -> 102: Add recurring_lifecycle_events table
val MIGRATION_101_102 = object : androidx.room.migration.Migration(101, 102) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS recurring_lifecycle_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                occurrenceId INTEGER,
                eventType TEXT NOT NULL,
                occurredAt INTEGER NOT NULL,
                oldStatus TEXT,
                newStatus TEXT,
                metadata TEXT
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_lifecycle_events_occurrenceId ON recurring_lifecycle_events (occurrenceId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_lifecycle_events_occurredAt ON recurring_lifecycle_events (occurredAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_recurring_lifecycle_events_eventType ON recurring_lifecycle_events (eventType)")
    }
}

// Migration 102 -> 103: Add privacy_audit_events table for privacy gate audit logging.
val MIGRATION_102_103 = object : androidx.room.migration.Migration(102, 103) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS privacy_audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                capability TEXT NOT NULL,
                decision TEXT NOT NULL,
                reason TEXT,
                context TEXT,
                timestampMs INTEGER NOT NULL,
                caller TEXT
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_privacy_audit_events_timestampMs ON privacy_audit_events (timestampMs)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_privacy_audit_events_capability ON privacy_audit_events (capability)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_privacy_audit_events_caller ON privacy_audit_events (caller)")
    }
}

// Migration 103 -> 104: Add raw data retention columns for privacy purging support.
val MIGRATION_103_104 = object : androidx.room.migration.Migration(103, 104) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER DEFAULT NULL")
        database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT DEFAULT NULL")
        database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER DEFAULT NULL")
    }
}

// Migration 104 -> 105: Phase 7 — Database Invariants.
// Adds materialized invariant key columns + unique indexes for:
//   budgets     — activeOverallKey, activeCategoryKey
//   group_members — currentUserGroupKey
//   group_expenses — expenseId unique index
//   raw_notifications — dedupeFingerprint
//   planned_expenses — openSourceOccurrenceKey
//
// TODO dedupeFingerprint hash mismatch:
//   The backfill below (line ~6414) uses plaintext concatenation
//   (packageName|timestamp|title|text|bigText) because SHA-256 is
//   not available in SQLite.  The runtime code in
//   RawNotificationFingerprint.compute() produces a SHA-256 hex
//   digest.  Any rows with a '|' in dedupeFingerprint are
//   plaintext and will NOT match runtime-computed fingerprints.
//   A migration or startup task should re-hash them using
//   RawNotificationFingerprint.compute().
val MIGRATION_104_105 = object : androidx.room.migration.Migration(104, 105) {
    override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
        val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
            it.moveToFirst(); it.getInt(0) == 1
        }
        if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

        try {
            database.beginTransaction()
            try {
                // ── Step 1: Heal duplicate data (before adding constraints) ───────

                // 1a. Budgets: deactivate duplicate active overall budgets (keep highest id)
                database.execSQL("""
                    UPDATE budgets SET isActive = 0
                    WHERE isActive = 1 AND categoryId IS NULL
                      AND id NOT IN (
                          SELECT MAX(id) FROM budgets
                          WHERE isActive = 1 AND categoryId IS NULL
                      )
                """.trimIndent())

                // 1b. Budgets: deactivate duplicate active category budgets (keep highest id per category)
                database.execSQL("""
                    UPDATE budgets SET isActive = 0
                    WHERE isActive = 1 AND categoryId IS NOT NULL
                      AND id NOT IN (
                          SELECT MAX(id) FROM budgets
                          WHERE isActive = 1 AND categoryId IS NOT NULL
                          GROUP BY categoryId
                      )
                """.trimIndent())

                // 1c. Group members: demote duplicate current users (keep highest id per group)
                database.execSQL("""
                    UPDATE group_members SET isCurrentUser = 0
                    WHERE isCurrentUser = 1
                      AND id NOT IN (
                          SELECT MAX(id) FROM group_members
                          WHERE isCurrentUser = 1
                          GROUP BY groupId
                      )
                """.trimIndent())

                // 1d. Group expenses: nullify duplicate expense links (keep earliest id)
                database.execSQL("""
                    UPDATE group_expenses SET expenseId = NULL
                    WHERE expenseId IS NOT NULL
                      AND id NOT IN (
                          SELECT MIN(id) FROM group_expenses
                          WHERE expenseId IS NOT NULL
                          GROUP BY expenseId
                      )
                """.trimIndent())

                // 1e. Planned expenses: supersede duplicate occurrence keys
                // For each duplicate sourceOccurrenceKey among PLANNED rows, keep the newest row,
                // set older rows to CANCELLED
                database.execSQL("""
                    UPDATE planned_expenses SET status = 'CANCELLED'
                    WHERE sourceOccurrenceKey IS NOT NULL
                      AND status = 'PLANNED'
                      AND id NOT IN (
                          SELECT MAX(id) FROM planned_expenses
                          WHERE sourceOccurrenceKey IS NOT NULL AND status = 'PLANNED'
                          GROUP BY sourceOccurrenceKey
                      )
                """.trimIndent())

                // ── Step 2: Add new columns ──────────────────────────────────────

                database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT DEFAULT NULL")

                // ── Step 3: Backfill materialized keys ───────────────────────────

                // Budgets: backfill activeOverallKey and activeCategoryKey
                database.execSQL("""
                    UPDATE budgets SET activeOverallKey = 1
                    WHERE isActive = 1 AND categoryId IS NULL
                """.trimIndent())
                database.execSQL("""
                    UPDATE budgets SET activeCategoryKey = categoryId
                    WHERE isActive = 1 AND categoryId IS NOT NULL
                """.trimIndent())

                // Group members: backfill currentUserGroupKey
                database.execSQL("""
                    UPDATE group_members SET currentUserGroupKey = groupId
                    WHERE isCurrentUser = 1
                """.trimIndent())

                // Planned expenses: backfill openSourceOccurrenceKey
                database.execSQL("""
                    UPDATE planned_expenses SET openSourceOccurrenceKey = sourceOccurrenceKey
                    WHERE status = 'PLANNED' AND sourceOccurrenceKey IS NOT NULL
                """.trimIndent())

                // Raw notifications: backfill dedupeFingerprint for existing rows
                // Uses a deterministic concatenation (SHA-256 not available in SQLite)
                database.execSQL("""
                    UPDATE raw_notifications SET dedupeFingerprint =
                      packageName || '|' || CAST(timestamp AS TEXT) || '|' ||
                      COALESCE(title, '') || '|' || COALESCE(text, '') || '|' ||
                      COALESCE(bigText, '')
                """.trimIndent())

                // ── Step 4: Drop old indexes & create new Room-compatible indexes ──

                // Budgets: recreate indexes including new unique ones
                database.execSQL("DROP INDEX IF EXISTS index_budgets_categoryId")
                database.execSQL("DROP INDEX IF EXISTS index_budgets_isActive")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey)")

                // Group members: drop old non-unique composite, create new unique key index
                database.execSQL("DROP INDEX IF EXISTS index_group_members_groupId_isCurrentUser")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")

                // Group expenses: drop old non-unique, create unique
                database.execSQL("DROP INDEX IF EXISTS index_group_expenses_expenseId")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_expenses_expenseId ON group_expenses (expenseId)")

                // Raw notifications: nullify duplicate fingerprints before creating unique index
                database.execSQL("""
                    UPDATE raw_notifications SET dedupeFingerprint = NULL
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM raw_notifications
                        WHERE dedupeFingerprint IS NOT NULL
                        GROUP BY dedupeFingerprint
                    )
                """.trimIndent())

                // Raw notifications: create unique fingerprint index
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedupeFingerprint ON raw_notifications (dedupeFingerprint)")

                // Planned expenses: create unique open occurrence key index
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_expenses_openSourceOccurrenceKey ON planned_expenses (openSourceOccurrenceKey)")

                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } finally {
            if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
        }
    }
}

        val MIGRATION_105_106 = object : androidx.room.migration.Migration(105, 106) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS background_job_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        workerName TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER DEFAULT NULL,
                        status TEXT NOT NULL DEFAULT 'RUNNING',
                        rowsScanned INTEGER NOT NULL DEFAULT 0,
                        rowsUpdated INTEGER NOT NULL DEFAULT 0,
                        notificationsSent INTEGER NOT NULL DEFAULT 0,
                        retryReason TEXT DEFAULT NULL,
                        errorMessage TEXT DEFAULT NULL
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_bg_job_runs_worker_started " +
                    "ON background_job_runs (workerName, startedAt)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_bg_job_runs_status " +
                    "ON background_job_runs (status)"
                )
            }
        }

        // Migration 106 -> 107: Phase 7 follow-up — CHECK constraints on materialized invariant keys.
        //
        // Adds DB-level CHECK constraints to enforce consistency of the materialized invariant
        // keys that were added in MIGRATION_104_105:
        //   budgets         — activeOverallKey, activeCategoryKey
        //   group_members   — currentUserGroupKey
        //   planned_expenses — openSourceOccurrenceKey
        //
        // Each constraint is a table-level CHECK that ensures the materialized key is
        // consistent with the business logic columns that drive it (isActive, categoryId,
        // isCurrentUser, status, sourceOccurrenceKey). This prevents raw insert() calls
        // from bypassing the repository-layer invariant maintenance.
        //
        // Table rebuilds are required because CHECK constraints are part of CREATE TABLE DDL
        // in SQLite and cannot be added via ALTER TABLE.
        val MIGRATION_106_107 = object : androidx.room.migration.Migration(106, 107) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                try {
                    database.beginTransaction()
                    try {
                        // ── 1. Budgets: rebuild with materialized key CHECK ──────────────
                        //
                        // Invariant:
                        //   - Inactive budget    → both keys NULL
                        //   - Active OVERALL     → activeOverallKey = 1, activeCategoryKey = NULL
                        //   - Active CATEGORY    → activeOverallKey = NULL, activeCategoryKey = categoryId
                        database.execSQL("""
                            CREATE TABLE budgets_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                categoryId INTEGER,
                                amount REAL NOT NULL CHECK(amount > 0),
                                period TEXT NOT NULL,
                                periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                                startDate INTEGER NOT NULL,
                                isActive INTEGER NOT NULL DEFAULT 1,
                                notifyAtWarning REAL NOT NULL DEFAULT 0.75 CHECK(notifyAtWarning > 0),
                                notifyAtCritical REAL NOT NULL DEFAULT 0.9 CHECK(notifyAtCritical > 0),
                                rollover INTEGER NOT NULL DEFAULT 0,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                                createdAt INTEGER NOT NULL,
                                lastWarningNotifiedAt INTEGER,
                                lastCriticalNotifiedAt INTEGER,
                                lastExceededNotifiedAt INTEGER,
                                activeOverallKey INTEGER,
                                activeCategoryKey INTEGER,
                                CHECK(notifyAtWarning <= notifyAtCritical),
                                CHECK(
                                    (isActive = 0 AND activeOverallKey IS NULL AND activeCategoryKey IS NULL)
                                    OR
                                    (isActive = 1 AND categoryId IS NULL AND activeOverallKey = 1 AND activeCategoryKey IS NULL)
                                    OR
                                    (isActive = 1 AND categoryId IS NOT NULL AND activeOverallKey IS NULL AND activeCategoryKey = categoryId)
                                ),
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                        """.trimIndent())

                            database.execSQL("INSERT INTO budgets_new (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, currency, currencyAssumption, createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt, activeOverallKey, activeCategoryKey) SELECT id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, currency, currencyAssumption, createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt, NULL, NULL FROM budgets")
                            database.execSQL("DROP TABLE budgets")
                            database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")

                            // Recreate Room-declared budgets indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey)")

                        // ── 2. Group members: rebuild with CHECK constraint ──────────────
                        //
                        // Invariant:
                        //   - Non-current-user → currentUserGroupKey IS NULL
                        //   - Current user     → currentUserGroupKey = groupId
                        database.execSQL("""
                            CREATE TABLE group_members_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                groupId INTEGER NOT NULL,
                                name TEXT NOT NULL,
                                email TEXT,
                                isCurrentUser INTEGER NOT NULL DEFAULT 0,
                                joinedAt INTEGER NOT NULL,
                                currentUserGroupKey INTEGER,
                                CHECK(
                                    (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
                                    OR
                                    (isCurrentUser = 1 AND currentUserGroupKey IS NOT NULL AND currentUserGroupKey = groupId)
                                ),
                                FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
                            )
                        """.trimIndent())

                        database.execSQL("""
                            INSERT INTO group_members_new (id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey)
                            SELECT id, groupId, name, email, isCurrentUser, joinedAt, NULL FROM group_members
                        """.trimIndent())
                        database.execSQL("DROP TABLE group_members")
                        database.execSQL("ALTER TABLE group_members_new RENAME TO group_members")

                        // Recreate Room-declared group_members indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_isCurrentUser ON group_members (groupId, isCurrentUser)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey)")

                        // ── 3. Planned expenses: rebuild with CHECK constraint ──────────
                        //
                        // Invariant:
                        //   - Non-PLANNED status → openSourceOccurrenceKey IS NULL
                        //   - PLANNED with sourceOccurrenceKey → openSourceOccurrenceKey = sourceOccurrenceKey (non-null)
                        //   - PLANNED without sourceOccurrenceKey → openSourceOccurrenceKey IS NULL
                        database.execSQL("""
                            CREATE TABLE planned_expenses_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                description TEXT NOT NULL,
                                amount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                                date INTEGER NOT NULL,
                                categoryId INTEGER,
                                isRecurring INTEGER NOT NULL DEFAULT 0,
                                priority TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                sourceOccurrenceKey TEXT,
                                sourceRecurringRuleId INTEGER,
                                status TEXT NOT NULL DEFAULT 'PLANNED',
                                linkedActualExpenseId INTEGER,
                                merchantKey TEXT,
                                updatedAt INTEGER NOT NULL,
                                openSourceOccurrenceKey TEXT,
                                CHECK(
                                    (status != 'PLANNED' AND openSourceOccurrenceKey IS NULL)
                                    OR
                                    (status = 'PLANNED' AND openSourceOccurrenceKey IS NOT NULL)
                                    OR
                                    (status = 'PLANNED' AND sourceOccurrenceKey IS NULL AND openSourceOccurrenceKey IS NULL)
                                ),
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                        """.trimIndent())

                        database.execSQL("""
                            INSERT INTO planned_expenses_new (id, description, amount, currency, currencyAssumption, date, categoryId, isRecurring, priority, createdAt, sourceOccurrenceKey, sourceRecurringRuleId, status, linkedActualExpenseId, merchantKey, updatedAt, openSourceOccurrenceKey)
                            SELECT id, description, amount, NULL, NULL, date, categoryId, isRecurring, priority, createdAt, sourceOccurrenceKey, sourceRecurringRuleId, status, linkedActualExpenseId, merchantKey, updatedAt, NULL FROM planned_expenses
                        """.trimIndent())
                        database.execSQL("DROP TABLE planned_expenses")
                        database.execSQL("ALTER TABLE planned_expenses_new RENAME TO planned_expenses")

                        // Recreate Room-declared planned_expenses indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_expenses_openSourceOccurrenceKey ON planned_expenses (openSourceOccurrenceKey)")

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // Migration 107 -> 108: Fix planned_expenses CHECK constraint.
        //
        // The CHECK constraint from MIGRATION_106_107 allowed a PLANNED row with
        // sourceOccurrenceKey = 'A' to have openSourceOccurrenceKey = 'B' (any non-null).
        // Fix: require openSourceOccurrenceKey to equal sourceOccurrenceKey when both are non-null.
        //
        // New invariant:
        //   - non-PLANNED status           → openSourceOccurrenceKey IS NULL
        //   - PLANNED + sourceKey IS NULL  → openSourceOccurrenceKey IS NULL
        //   - PLANNED + sourceKey NOT NULL → openSourceOccurrenceKey = sourceOccurrenceKey
        val MIGRATION_107_108 = object : androidx.room.migration.Migration(107, 108) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                try {
                    database.beginTransaction()
                    try {
                        // Step 1: Heal bad rows before rebuilding the table
                        // For PLANNED rows: set openSourceOccurrenceKey = sourceOccurrenceKey
                        // For non-PLANNED rows: set openSourceOccurrenceKey = NULL
                        database.execSQL("""
                            UPDATE planned_expenses
                            SET openSourceOccurrenceKey = sourceOccurrenceKey
                            WHERE status = 'PLANNED'
                        """.trimIndent())
                        database.execSQL("""
                            UPDATE planned_expenses
                            SET openSourceOccurrenceKey = NULL
                            WHERE status != 'PLANNED'
                        """.trimIndent())

                        // Step 2: Rebuild table with stricter CHECK constraint
                        database.execSQL("""
                            CREATE TABLE planned_expenses_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                description TEXT NOT NULL,
                                amount REAL NOT NULL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                                date INTEGER NOT NULL,
                                categoryId INTEGER,
                                isRecurring INTEGER NOT NULL DEFAULT 0,
                                priority TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                sourceOccurrenceKey TEXT,
                                sourceRecurringRuleId INTEGER,
                                status TEXT NOT NULL DEFAULT 'PLANNED',
                                linkedActualExpenseId INTEGER,
                                merchantKey TEXT,
                                updatedAt INTEGER NOT NULL,
                                openSourceOccurrenceKey TEXT,
                                CHECK(
                                    (status != 'PLANNED' AND openSourceOccurrenceKey IS NULL)
                                    OR
                                    (status = 'PLANNED' AND sourceOccurrenceKey IS NULL AND openSourceOccurrenceKey IS NULL)
                                    OR
                                    (status = 'PLANNED' AND sourceOccurrenceKey IS NOT NULL AND openSourceOccurrenceKey = sourceOccurrenceKey)
                                ),
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE SET NULL
                            )
                        """.trimIndent())

                        database.execSQL("""
                            INSERT INTO planned_expenses_new (id, description, amount, currency, currencyAssumption, date, categoryId, isRecurring, priority, createdAt, sourceOccurrenceKey, sourceRecurringRuleId, status, linkedActualExpenseId, merchantKey, updatedAt, openSourceOccurrenceKey)
                            SELECT id, description, amount, currency, currencyAssumption, date, categoryId, isRecurring, priority, createdAt, sourceOccurrenceKey, sourceRecurringRuleId, status, linkedActualExpenseId, merchantKey, updatedAt, openSourceOccurrenceKey FROM planned_expenses
                        """.trimIndent())
                        database.execSQL("DROP TABLE planned_expenses")
                        database.execSQL("ALTER TABLE planned_expenses_new RENAME TO planned_expenses")

                        // Recreate Room-declared planned_expenses indexes
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_date ON planned_expenses (date)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_planned_expenses_categoryId ON planned_expenses (categoryId)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_expenses_openSourceOccurrenceKey ON planned_expenses (openSourceOccurrenceKey)")

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // MIGRATION_108_109 — Batches R+S
        // ═════════════════════════════════════════════════════════════════════
        //
        // R1:  Warranty/ReturnWindow receiptId FK CASCADE→SET_NULL, receiptId→nullable
        // R5:  ReturnWindow.refundCurrency column
        // S1:  group_expenses paidById same-group trigger
        // S4:  group_members currentUserGroupKey CHECK constraint activation
        //
        // Strategy:
        //   1. Rebuild warranties    (receiptId nullable, FK SET_NULL)
        //   2. Rebuild return_windows (receiptId nullable, FK SET_NULL, +refundCurrency)
        //   3. Rebuild group_members  (re-apply CHECK constraint for upgrade safety)
        //   4. CREATE TRIGGER enforce_paid_by_same_group
        val MIGRATION_108_109 = object : androidx.room.migration.Migration(108, 109) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                try {
                    database.beginTransaction()
                    try {
                        // ── R1+R5: Rebuild warranties ──────────────────────────
                        // receiptId: nullable, FK changed from CASCADE to SET_NULL
                        database.execSQL("""
                            CREATE TABLE warranties_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                receiptId INTEGER,
                                expenseId INTEGER,
                                productName TEXT NOT NULL,
                                merchantName TEXT NOT NULL,
                                purchaseDate INTEGER NOT NULL,
                                warrantyDurationMonths INTEGER NOT NULL,
                                warrantyEndDate INTEGER NOT NULL,
                                warrantyType TEXT NOT NULL DEFAULT 'MANUFACTURER',
                                supportPhone TEXT,
                                supportEmail TEXT,
                                warrantyDocumentUrl TEXT,
                                notes TEXT,
                                status TEXT NOT NULL DEFAULT 'ACTIVE',
                                claimedAt INTEGER,
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL,
                                autoDetected INTEGER NOT NULL DEFAULT 0,
                                extractionConfidence REAL NOT NULL DEFAULT 0.0,
                                extractionSource TEXT NOT NULL DEFAULT 'manual',
                                needsReview INTEGER NOT NULL DEFAULT 0,
                                FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL,
                                FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                            )
                        """.trimIndent())
                        database.execSQL("""
                            INSERT INTO warranties_new (id, receiptId, expenseId, productName, merchantName, purchaseDate, warrantyDurationMonths, warrantyEndDate, warrantyType, supportPhone, supportEmail, warrantyDocumentUrl, notes, status, claimedAt, createdAt, updatedAt, autoDetected, extractionConfidence, extractionSource, needsReview)
                            SELECT id, receiptId, expenseId, productName, merchantName, purchaseDate, warrantyDurationMonths, warrantyEndDate, warrantyType, supportPhone, supportEmail, warrantyDocumentUrl, notes, status, claimedAt, createdAt, updatedAt, autoDetected, extractionConfidence, extractionSource, needsReview FROM warranties
                        """.trimIndent())
                        database.execSQL("DROP TABLE warranties")
                        database.execSQL("ALTER TABLE warranties_new RENAME TO warranties")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_expenseId ON warranties (expenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_warrantyEndDate ON warranties (warrantyEndDate)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_status ON warranties (status)")

                        // ── R1+R5: Rebuild return_windows ──────────────────────
                        // receiptId: nullable, FK changed from CASCADE to SET_NULL
                        // +refundCurrency column
                        database.execSQL("""
                            CREATE TABLE return_windows_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                receiptId INTEGER,
                                expenseId INTEGER,
                                productName TEXT NOT NULL,
                                merchantName TEXT NOT NULL,
                                purchaseDate INTEGER NOT NULL,
                                returnDays INTEGER NOT NULL,
                                returnDeadline INTEGER NOT NULL,
                                returnPolicyUrl TEXT,
                                returnConditions TEXT,
                                status TEXT NOT NULL DEFAULT 'RETURNABLE',
                                returnedAt INTEGER,
                                refundAmount REAL,
                                refundCurrency TEXT DEFAULT NULL,
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL,
                                FOREIGN KEY (receiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL,
                                FOREIGN KEY (expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                            )
                        """.trimIndent())
                        database.execSQL("""
                            INSERT INTO return_windows_new (
                                id, receiptId, expenseId, productName, merchantName,
                                purchaseDate, returnDays, returnDeadline, returnPolicyUrl,
                                returnConditions, status, returnedAt, refundAmount,
                                createdAt, updatedAt
                            )
                            SELECT
                                id, receiptId, expenseId, productName, merchantName,
                                purchaseDate, returnDays, returnDeadline, returnPolicyUrl,
                                returnConditions, status, returnedAt, refundAmount,
                                createdAt, updatedAt
                            FROM return_windows
                        """.trimIndent())
                        database.execSQL("DROP TABLE return_windows")
                        database.execSQL("ALTER TABLE return_windows_new RENAME TO return_windows")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_receiptId ON return_windows (receiptId)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_return_windows_expenseId ON return_windows (expenseId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_returnDeadline ON return_windows (returnDeadline)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_return_windows_status ON return_windows (status)")

                        // ── S4: Rebuild group_members to activate CHECK constraint ─
                        // Re-apply the CHECK constraint from MIGRATION_106_107 so that
                        // upgrade paths that skipped it get it now.
                        database.execSQL("""
                            CREATE TABLE group_members_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                groupId INTEGER NOT NULL,
                                name TEXT NOT NULL,
                                email TEXT,
                                isCurrentUser INTEGER NOT NULL DEFAULT 0,
                                joinedAt INTEGER NOT NULL,
                                currentUserGroupKey INTEGER,
                                CHECK(
                                    (isCurrentUser = 0 AND currentUserGroupKey IS NULL)
                                    OR
                                    (isCurrentUser = 1 AND currentUserGroupKey IS NOT NULL AND currentUserGroupKey = groupId)
                                ),
                                FOREIGN KEY (groupId) REFERENCES expense_groups(id) ON DELETE CASCADE
                            )
                        """.trimIndent())
                        database.execSQL("""
                            INSERT INTO group_members_new (id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey)
                            SELECT id, groupId, name, email, isCurrentUser, joinedAt, currentUserGroupKey FROM group_members
                        """.trimIndent())
                        database.execSQL("DROP TABLE group_members")
                        database.execSQL("ALTER TABLE group_members_new RENAME TO group_members")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId ON group_members (groupId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_groupId_isCurrentUser ON group_members (groupId, isCurrentUser)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_groupId_name ON group_members (groupId, name)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_group_members_currentUserGroupKey ON group_members (currentUserGroupKey)")

                        // ── S1: Create paidById same-group trigger ──────────────
                        database.execSQL("""
                            CREATE TRIGGER IF NOT EXISTS enforce_paid_by_same_group
                            BEFORE INSERT ON group_expenses
                            BEGIN
                                SELECT CASE WHEN (
                                    SELECT groupId FROM group_members WHERE id = NEW.paidById
                                ) != NEW.groupId
                                THEN RAISE(ABORT, 'paidById must belong to same group') END;
                            END
                        """.trimIndent())

                        // ── Verify FK integrity ─────────────────────────────────
                        database.query("PRAGMA foreign_key_check").use { violations ->
                            if (violations.moveToFirst()) {
                                throw IllegalStateException(
                                    "Migration 108→109 produced FK violations"
                                )
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // MIGRATION_109_110 — Y1 + Y8
        // ═════════════════════════════════════════════════════════════════════
        //
        // Y1: Make rawNotificationId unique on expenses
        //   - Nullify duplicate rawNotificationIds (keep latest expense per non-null id)
        //   - Drop old non-unique index, create unique index
        //
        // Y8: Backfill remaining NULL dedupeFingerprint in raw_notifications
        //   - MIGRATION_104_105 backfilled and then nullified duplicates.
        //   - This step backfills any rows still NULL using the same deterministic formula.
        val MIGRATION_109_110 = object : androidx.room.migration.Migration(109, 110) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                try {
                    database.beginTransaction()
                    try {
                        // ── Y8: Backfill remaining NULL dedupeFingerprint ──────────
                        // Uses the same deterministic concatenation as MIGRATION_104_105
                        // (not SHA-256, which the Kotlin code uses for new rows).
                        database.execSQL("""
                            UPDATE raw_notifications SET dedupeFingerprint =
                              packageName || '|' || CAST(timestamp AS TEXT) || '|' ||
                              COALESCE(title, '') || '|' || COALESCE(text, '') || '|' ||
                              COALESCE(bigText, '')
                            WHERE dedupeFingerprint IS NULL
                        """.trimIndent())

                        // Nullify any newly-created duplicate fingerprints
                        database.execSQL("""
                            UPDATE raw_notifications SET dedupeFingerprint = NULL
                            WHERE id NOT IN (
                                SELECT MIN(id) FROM raw_notifications
                                WHERE dedupeFingerprint IS NOT NULL
                                GROUP BY dedupeFingerprint
                            )
                        """.trimIndent())

                        // ── Y1: Make rawNotificationId unique on expenses ─────────
                        // Nullify duplicate rawNotificationIds (keep latest expense)
                        database.execSQL("""
                            UPDATE expenses SET rawNotificationId = NULL
                            WHERE rawNotificationId IS NOT NULL
                              AND id NOT IN (
                                  SELECT MAX(id) FROM expenses
                                  WHERE rawNotificationId IS NOT NULL
                                  GROUP BY rawNotificationId
                              )
                        """.trimIndent())

                        // Drop old non-unique index and create unique index
                        database.execSQL("DROP INDEX IF EXISTS index_expenses_rawNotificationId")
                        database.execSQL("""
                            CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_rawNotificationId
                            ON expenses (rawNotificationId)
                        """.trimIndent())

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // MIGRATION_110_111 — CURR-2 + TRN-2
        // ═════════════════════════════════════════════════════════════════════
        //
        // CURR-2: Change the unique index on exchange_rates from (fromCurrency, toCurrency)
        //   to (fromCurrency, toCurrency, validDate) so that multiple rates can
        //   coexist for the same currency pair at different dates, enabling
        //   historically-accurate currency conversion in reports.
        //
        // TRN-2: Rebuild pending_reviews table to make suggestedAmount nullable (replace
        //   the synthetic 0.01 sentinel with explicit null).
        val MIGRATION_110_111 = object : androidx.room.migration.Migration(110, 111) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                // ── CURR-2: Exchange rates historical index ─────────────────────────
                // 0. Ensure validDate column exists (may be missing on very old installs)
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }

                // 1. Nullify any duplicate (fromCurrency, toCurrency, validDate) combinations
                //    before creating the new unique index.  With the previous unique on
                //    (fromCurrency, toCurrency) this is a safety measure for edge cases.
                database.execSQL("""
                    DELETE FROM exchange_rates WHERE id NOT IN (
                        SELECT MIN(id) FROM exchange_rates
                        GROUP BY fromCurrency, toCurrency, COALESCE(validDate, 0)
                    )
                """.trimIndent())

                // 2. Ensure validDate is never NULL (default 0 for legacy rows)
                database.execSQL("UPDATE exchange_rates SET validDate = 0 WHERE validDate IS NULL")

                // 3. Drop old unique index (may have different name depending on
                //    whether the DB was created by migration or fresh install)
                database.execSQL("DROP INDEX IF EXISTS index_exchange_rates_from_to")
                database.execSQL("DROP INDEX IF EXISTS index_exchange_rates_fromCurrency_toCurrency")

                // 4. Drop old non-unique composite index (will be replaced by unique)
                database.execSQL("DROP INDEX IF EXISTS index_exchange_rates_fromCurrency_toCurrency_validDate")

                // 5. Create new unique index on (fromCurrency, toCurrency, validDate)
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_exchange_rates_fromCurrency_toCurrency_validDate
                    ON exchange_rates (fromCurrency, toCurrency, validDate)
                """.trimIndent())

                // ── TRN-2: Make suggestedAmount nullable in pending_reviews ────────
                // SQLite cannot ALTER COLUMN, so we rebuild the table with a nullable
                // suggestedAmount column (REAL without NOT NULL).
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // Ensure extractionState column exists (may be missing on older installs)
                        try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT") } catch (_: Exception) { }

                        // Create new table with nullable suggestedAmount
                        database.execSQL("""
                            CREATE TABLE IF NOT EXISTS pending_reviews_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                rawNotificationId INTEGER,
                                scannedReceiptId INTEGER,
                                suggestedAmount REAL,
                                suggestedCurrency TEXT NOT NULL,
                                suggestedMerchant TEXT NOT NULL,
                                suggestedMerchantKey TEXT,
                                suggestedType TEXT NOT NULL,
                                suggestedCategoryId INTEGER,
                                suggestedDate INTEGER,
                                confidence REAL NOT NULL,
                                matchType TEXT,
                                explanation TEXT,
                                packageName TEXT NOT NULL,
                                notificationTitle TEXT,
                                notificationText TEXT,
                                createdAt INTEGER NOT NULL,
                                status TEXT NOT NULL DEFAULT 'PENDING',
                                suggestedDirection TEXT,
                                suggestedAccountName TEXT,
                                suggestedLatitude REAL,
                                suggestedLongitude REAL,
                                extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION',
                                FOREIGN KEY (rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL,
                                FOREIGN KEY (scannedReceiptId) REFERENCES scanned_receipts(id) ON DELETE SET NULL
                            )
                        """.trimIndent())

                        // Copy all existing data; suggestedAmount remains as-is for real rows,
                        // synthetic placeholder rows (which had 0.01) will be set to NULL.
                        // The 0.01→NULL conversion for synthetic placeholders is handled in
                        // application code (ReviewQueueRepository now creates reviews with
                        // suggestedAmount = null for SYNTHETIC_PLACEHOLDER extractionState).
                        database.execSQL("""
                            INSERT INTO pending_reviews_new
                            SELECT
                                id, rawNotificationId, scannedReceiptId,
                                CASE WHEN extractionState = 'SYNTHETIC_PLACEHOLDER' THEN NULL ELSE suggestedAmount END,
                                suggestedCurrency, suggestedMerchant, suggestedMerchantKey,
                                suggestedType, suggestedCategoryId, suggestedDate,
                                confidence, matchType, explanation,
                                packageName, notificationTitle, notificationText,
                                createdAt, status,
                                suggestedDirection, suggestedAccountName,
                                suggestedLatitude, suggestedLongitude,
                                COALESCE(extractionState, 'REAL_EXTRACTION')
                            FROM pending_reviews
                        """.trimIndent())

                        // Swap tables
                        database.execSQL("DROP TABLE pending_reviews")
                        database.execSQL("ALTER TABLE pending_reviews_new RENAME TO pending_reviews")

                        // Recreate indices
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_suggestedMerchantKey ON pending_reviews (suggestedMerchantKey)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_suggestedMerchantKey_suggestedDate ON pending_reviews (status, suggestedMerchantKey, suggestedDate)")

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // ═════════════════════════════════════════════════════════════════════
        // MIGRATION_111_112 — BUD-1: budgets categoryId FK RESTRICT
        // ═════════════════════════════════════════════════════════════════════
        //
        // BUD-1: Change budgets.categoryId FK from SET_NULL to RESTRICT so that
        //   deleting a Category with active budgets fails fast instead of silently
        //   converting category budgets into overall budgets.
        val MIGRATION_111_112 = object : androidx.room.migration.Migration(111, 112) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                // Ensure all columns from the Room entity exist (safe-guard for skip-migration paths)
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN rawContentPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN parseResult TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE raw_notifications ADD COLUMN dedupeFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN rawOcrTextPurgedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN documentType TEXT NOT NULL DEFAULT 'UNKNOWN'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN processingStatus TEXT NOT NULL DEFAULT 'CAPTURED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN sourceFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN imageHash TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN textFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN semanticFingerprint TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN ocrConfidence REAL") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN parseFailureReason TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE scanned_receipts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE expenses ADD COLUMN source TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN validDate INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE exchange_rates ADD COLUMN fetchedAt INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE pending_reviews ADD COLUMN extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeOverallKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE budgets ADD COLUMN activeCategoryKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE group_members ADD COLUMN currentUserGroupKey INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN openSourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceOccurrenceKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN sourceRecurringRuleId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN status TEXT NOT NULL DEFAULT 'PLANNED'") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN linkedActualExpenseId INTEGER") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN merchantKey TEXT") } catch (_: Exception) { }
                try { database.execSQL("ALTER TABLE planned_expenses ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) { }

                try {
                    database.beginTransaction()
                    try {
                        // Rebuild budgets with RESTRICT on categoryId FK
                        database.execSQL("""
                            CREATE TABLE budgets_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                categoryId INTEGER,
                                amount REAL NOT NULL,
                                period TEXT NOT NULL,
                                periodMode TEXT NOT NULL DEFAULT 'ROLLING',
                                startDate INTEGER NOT NULL,
                                isActive INTEGER NOT NULL DEFAULT 1,
                                notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                                notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                                rollover INTEGER NOT NULL DEFAULT 0,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                currencyAssumption TEXT NOT NULL DEFAULT 'LEGACY_DEFAULT',
                                createdAt INTEGER NOT NULL DEFAULT 0,
                                lastWarningNotifiedAt INTEGER,
                                lastCriticalNotifiedAt INTEGER,
                                lastExceededNotifiedAt INTEGER,
                                activeOverallKey INTEGER,
                                activeCategoryKey INTEGER,
                                FOREIGN KEY (categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                            )
                        """.trimIndent())
                        database.execSQL("""
                            INSERT INTO budgets_new (id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, currency, currencyAssumption, createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt, activeOverallKey, activeCategoryKey)
                            SELECT id, categoryId, amount, period, periodMode, startDate, isActive, notifyAtWarning, notifyAtCritical, rollover, currency, currencyAssumption, createdAt, lastWarningNotifiedAt, lastCriticalNotifiedAt, lastExceededNotifiedAt, activeOverallKey, activeCategoryKey FROM budgets
                        """.trimIndent())
                        database.execSQL("DROP TABLE budgets")
                        database.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeOverallKey ON budgets (activeOverallKey)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_activeCategoryKey ON budgets (activeCategoryKey)")

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // MIGRATION_112_113 — Category name uniqueness with COLLATE NOCASE
        // ═════════════════════════════════════════════════════════════════════
        //
        // Adds a UNIQUE index on categories(name COLLATE NOCASE) to enforce
        // case-insensitive category name uniqueness at the DB level.
        // See also Category.normalizedName and CategoryRepository.addCategory
        // for application-level deduplication.
        val MIGRATION_112_113 = object : androidx.room.migration.Migration(112, 113) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Deduplicate existing categories that differ only by case.
                // Keep the first occurrence (lowest id) for each case-insensitive name.
                database.execSQL("""
                    DELETE FROM categories WHERE id NOT IN (
                        SELECT MIN(id) FROM categories GROUP BY name COLLATE NOCASE
                    )
                """.trimIndent())

                // Create unique index with NOCASE collation
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_categories_name_nocase
                    ON categories(name COLLATE NOCASE)
                """.trimIndent())
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // MIGRATION_113_114 — WRN-6, WRN-13, BUD-12
        // ═══════════════════════════════════════════════════════════════════════
        //
        // WRN-6:  Drop UNIQUE index on warranties(receiptId), recreate as non-unique
        //         so multiple warranties per receipt are allowed.
        // WRN-13: Add refundExpenseId column to return_windows (FK to expenses,
        //         ON DELETE SET NULL) for linking returns to refund transactions.
        // BUD-12: Add rolloverDeficitTracking column to budgets (default 0 / false)
        //         for carrying deficits forward in rollover calculation.
        val MIGRATION_113_114 = object : androidx.room.migration.Migration(113, 114) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // WRN-6: Drop unique index and recreate as non-unique
                database.execSQL("DROP INDEX IF EXISTS index_warranties_receiptId")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_warranties_receiptId ON warranties (receiptId)")

                // WRN-13: Add refundExpenseId column to return_windows
                // FK constraint is not added via ALTER because SQLite does not
                // support ALTER TABLE ADD CONSTRAINT. The FK is enforced at the
                // Room entity level via the @Entity(foreignKeys = [...]) annotation.
                database.execSQL("ALTER TABLE return_windows ADD COLUMN refundExpenseId INTEGER DEFAULT NULL")

                // BUD-12: Add rolloverDeficitTracking column to budgets
                database.execSQL("ALTER TABLE budgets ADD COLUMN rolloverDeficitTracking INTEGER NOT NULL DEFAULT 0")
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // MIGRATION_114_115 — I8: DB invariants for BudgetForecast
        // ═══════════════════════════════════════════════════════════════════════
        //
        // I8: Add unique index on budget_forecasts(budgetId, forecastDate) to
        //     prevent duplicate forecasts for the same budget at the same moment.
        val MIGRATION_114_115 = object : androidx.room.migration.Migration(114, 115) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_budgetId_forecastDate " +
                    "ON budget_forecasts(budgetId, forecastDate)"
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // MIGRATION_115_116 — DB-8: BudgetForecast FK from CASCADE to RESTRICT
        // ═══════════════════════════════════════════════════════════════════════
        //
        // DB-8: Change budget_forecasts.budgetId FK from ON DELETE CASCADE to
        // ON DELETE RESTRICT to preserve historical forecasts for analytical
        // value (accuracy tracking, trend analysis).
        //
        // Since SQLite does not support ALTER TABLE to change a foreign key
        // constraint, we use the table-rebuild pattern: CREATE, COPY, DROP, RENAME.
        val MIGRATION_115_116 = object : androidx.room.migration.Migration(115, 116) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Preserve FK state, disable temporarily for the rebuild
                val fkWasEnabled = database.query("PRAGMA foreign_keys").use {
                    it.moveToFirst(); it.getInt(0) == 1
                }
                if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=OFF")

                try {
                    database.beginTransaction()
                    try {
                        // Create new table with RESTRICT FK
                        database.execSQL("""
                            CREATE TABLE budget_forecasts_new (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                budgetId INTEGER NOT NULL,
                                forecastDate INTEGER NOT NULL,
                                targetPeriodStart INTEGER NOT NULL,
                                targetPeriodEnd INTEGER NOT NULL,
                                predictedSpending REAL NOT NULL,
                                predictedRemaining REAL NOT NULL,
                                confidenceScore REAL NOT NULL,
                                riskLevel TEXT NOT NULL,
                                overspendProbability REAL NOT NULL,
                                recommendationsJson TEXT,
                                actualSpending REAL,
                                forecastAccuracy REAL,
                                currency TEXT NOT NULL DEFAULT 'EUR',
                                isActive INTEGER NOT NULL DEFAULT 1,
                                createdAt INTEGER NOT NULL,
                                FOREIGN KEY(budgetId) REFERENCES budgets(id) ON DELETE RESTRICT
                            )
                        """.trimIndent())

                        // Copy all data
                        database.execSQL("""
                            INSERT INTO budget_forecasts_new (
                                id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                                predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                                overspendProbability, recommendationsJson, actualSpending,
                                forecastAccuracy, currency, isActive, createdAt
                            )
                            SELECT
                                id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                                predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                                overspendProbability, recommendationsJson, actualSpending,
                                forecastAccuracy, currency, isActive, createdAt
                            FROM budget_forecasts
                        """.trimIndent())

                        // Swap tables
                        database.execSQL("DROP TABLE budget_forecasts")
                        database.execSQL("ALTER TABLE budget_forecasts_new RENAME TO budget_forecasts")

                        // Recreate indices
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId ON budget_forecasts (budgetId)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate ON budget_forecasts (forecastDate)")
                        database.execSQL("CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive ON budget_forecasts (isActive)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_budgetId_targetPeriodStart ON budget_forecasts (budgetId, targetPeriodStart)")
                        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budget_forecasts_budgetId_forecastDate ON budget_forecasts (budgetId, forecastDate)")

                        // Verify no FK violations
                        if (fkWasEnabled) {
                            database.query("PRAGMA foreign_key_check").use { violations ->
                                if (violations.moveToFirst()) {
                                    throw IllegalStateException(
                                        "MIGRATION_115_116 produced FK violations"
                                    )
                                }
                            }
                        }

                        database.setTransactionSuccessful()
                    } finally {
                        database.endTransaction()
                    }
                } finally {
                    if (fkWasEnabled) database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        val MIGRATION_116_117 = object : androidx.room.migration.Migration(116, 117) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_stats_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        expenseId INTEGER,
                        rawNotificationId INTEGER,
                        metadata TEXT,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL,
                        FOREIGN KEY(rawNotificationId) REFERENCES raw_notifications(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_stats_events_packageName ON source_stats_events (packageName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_stats_events_eventType ON source_stats_events (eventType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_stats_events_timestamp ON source_stats_events (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_stats_events_expenseId ON source_stats_events (expenseId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_source_stats_events_rawNotificationId ON source_stats_events (rawNotificationId)")
            }
        }

        val MIGRATION_117_118 = object : androidx.room.migration.Migration(117, 118) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS warranty_lifecycle_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        warrantyId INTEGER NOT NULL,
                        eventType TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        description TEXT,
                        metadata TEXT
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_warranty_lifecycle_events_warrantyId ON warranty_lifecycle_events(warrantyId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_warranty_lifecycle_events_occurredAt ON warranty_lifecycle_events(occurredAt)")
            }
        }

        val MIGRATION_118_119 = object : androidx.room.migration.Migration(118, 119) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS investment_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        holdingId INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        pricePerUnit REAL NOT NULL,
                        totalAmount REAL NOT NULL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        fee REAL NOT NULL DEFAULT 0.0,
                        date INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_investment_transactions_holdingId ON investment_transactions(holdingId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_investment_transactions_date ON investment_transactions(date)")
            }
        }

        val MIGRATION_119_120 = object : androidx.room.migration.Migration(119, 120) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_settlements (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER NOT NULL,
                        fromMemberId INTEGER NOT NULL,
                        toMemberId INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        currency TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        linkedExpenseId INTEGER,
                        status TEXT NOT NULL DEFAULT 'RECORDED',
                        notes TEXT,
                        FOREIGN KEY(groupId) REFERENCES expense_groups(id) ON DELETE CASCADE,
                        FOREIGN KEY(fromMemberId) REFERENCES group_members(id) ON DELETE CASCADE,
                        FOREIGN KEY(toMemberId) REFERENCES group_members(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_settlements_groupId ON group_settlements (groupId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_settlements_fromMemberId ON group_settlements (fromMemberId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_settlements_toMemberId ON group_settlements (toMemberId)")
            }
        }

        val MIGRATION_120_121 = object : androidx.room.migration.Migration(120, 121) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_lifecycle_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        groupId INTEGER NOT NULL,
                        eventType TEXT NOT NULL,
                        actorMemberId INTEGER,
                        relatedExpenseId INTEGER,
                        relatedSettlementId INTEGER,
                        payloadJson TEXT,
                        createdAt INTEGER NOT NULL,
                        source TEXT NOT NULL DEFAULT 'GROUP_LIFECYCLE'
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_lifecycle_events_groupId ON group_lifecycle_events (groupId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_lifecycle_events_eventType ON group_lifecycle_events (eventType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_group_lifecycle_events_createdAt ON group_lifecycle_events (createdAt)")
            }
        }

        val MIGRATION_121_122 = object : androidx.room.migration.Migration(121, 122) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS pipeline_diagnostic_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pipeline TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        packageName TEXT,
                        sourceId INTEGER,
                        dropReason TEXT,
                        message TEXT,
                        timestamp INTEGER NOT NULL,
                        entityType TEXT,
                        entityId INTEGER,
                        exceptionClass TEXT,
                        exceptionMessage TEXT,
                        metadataJson TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_pipeline_stage ON pipeline_diagnostic_events (pipeline, stage)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_timestamp ON pipeline_diagnostic_events (timestamp)")
            }
        }

        val MIGRATION_122_123 = object : androidx.room.migration.Migration(122, 123) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // No-op: pipeline_diagnostic_events table was created fresh in MIGRATION_121_122
                // with all columns (entityType, entityId, exceptionClass, exceptionMessage, metadataJson)
                // already included. ALTER TABLE would cause "duplicate column name" crash.
            }
        }

        val MIGRATION_123_124 = object : androidx.room.migration.Migration(123, 124) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN notificationKeyHash TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN confidence REAL")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN decisionSource TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN elapsedMs INTEGER")
            }
        }

        val MIGRATION_124_125 = object : androidx.room.migration.Migration(124, 125) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE background_job_runs ADD COLUMN statusReason TEXT")
            }
        }

        val MIGRATION_125_126 = object : androidx.room.migration.Migration(125, 126) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Enhance pipeline_diagnostic_events
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN eventId TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN correlationId TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN causationId TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN severity TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN reasonCode TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN sourceType TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN sourceIdHash TEXT")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN isTerminal INTEGER")
                database.execSQL("ALTER TABLE pipeline_diagnostic_events ADD COLUMN metadataSchemaVersion INTEGER NOT NULL DEFAULT 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_correlationId ON pipeline_diagnostic_events(correlationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_reasonCode ON pipeline_diagnostic_events(reasonCode)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pipeline_diagnostic_events_entity ON pipeline_diagnostic_events(entityType, entityId)")

                // Enhance background_job_runs
                database.execSQL("ALTER TABLE background_job_runs ADD COLUMN correlationId TEXT")
                database.execSQL("ALTER TABLE background_job_runs ADD COLUMN cancellationReason TEXT")
                database.execSQL("ALTER TABLE background_job_runs ADD COLUMN metadataJson TEXT")
                database.execSQL("ALTER TABLE background_job_runs ADD COLUMN errorClass TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_background_job_runs_correlationId ON background_job_runs(correlationId)")

                // Create operation_runs table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS operation_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        correlationId TEXT NOT NULL,
                        operationType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER,
                        actor TEXT,
                        rowsTotal INTEGER,
                        rowsProcessed INTEGER NOT NULL DEFAULT 0,
                        rowsSucceeded INTEGER NOT NULL DEFAULT 0,
                        rowsFailed INTEGER NOT NULL DEFAULT 0,
                        rowsSkipped INTEGER NOT NULL DEFAULT 0,
                        warningCount INTEGER NOT NULL DEFAULT 0,
                        errorCount INTEGER NOT NULL DEFAULT 0,
                        metadataJson TEXT,
                        errorSummary TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_runs_operationType_startedAt ON operation_runs(operationType, startedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_runs_status ON operation_runs(status)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_operation_runs_correlationId ON operation_runs(correlationId)")

                // Create operation_run_events table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS operation_run_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        operationRunId INTEGER,
                        correlationId TEXT NOT NULL,
                        causationId TEXT,
                        operationType TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        reasonCode TEXT,
                        occurredAt INTEGER NOT NULL,
                        entityType TEXT,
                        entityId INTEGER,
                        metadataJson TEXT,
                        exceptionClass TEXT,
                        exceptionMessage TEXT
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_run_events_operationRunId ON operation_run_events(operationRunId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_run_events_correlationId ON operation_run_events(correlationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_run_events_eventType ON operation_run_events(eventType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_run_events_occurredAt ON operation_run_events(occurredAt)")

                // P3-EB0-09: Fresh-install partial unique indexes on receipt fingerprints
                // (mirrors MIGRATION_137_138 for upgraded DBs).
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fresh_imageHash_u ON scanned_receipts(imageHash) WHERE imageHash IS NOT NULL AND imageHash != ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fresh_sourceFp_u ON scanned_receipts(sourceFingerprint) WHERE sourceFingerprint IS NOT NULL AND sourceFingerprint != ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fresh_textFp_u ON scanned_receipts(textFingerprint) WHERE textFingerprint IS NOT NULL AND textFingerprint != ''")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fresh_semFp_u ON scanned_receipts(semanticFingerprint) WHERE semanticFingerprint IS NOT NULL AND semanticFingerprint != ''")

                // P3-BLOCKER-C: Fresh-install unique partial index on emailMessageIdHash.
                // Column must exist before index creation (table was created without it in earlier migration).
                database.execSQL("ALTER TABLE email_receipt_sources ADD COLUMN emailMessageIdHash TEXT")
                database.execSQL("ALTER TABLE email_receipt_sources ADD COLUMN contentFingerprintHash TEXT")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fresh_emailMsgIdHash_u ON email_receipt_sources(emailMessageIdHash) WHERE emailMessageIdHash IS NOT NULL AND emailMessageIdHash != ''")
            }
        }

        val MIGRATION_126_127 = object : androidx.room.migration.Migration(126, 127) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE operation_run_events ADD COLUMN isTerminal INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_127_128 = object : androidx.room.migration.Migration(127, 128) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE operation_run_events ADD COLUMN eventId TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_operation_run_events_eventId ON operation_run_events(eventId)")
            }
        }

        val MIGRATION_128_129 = object : androidx.room.migration.Migration(128, 129) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transaction_events ADD COLUMN correlationId TEXT")
                database.execSQL("ALTER TABLE transaction_events ADD COLUMN causationId TEXT")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_transaction_events_correlationId ON transaction_events(correlationId)")
            }
        }

        val MIGRATION_129_130 = object : androidx.room.migration.Migration(129, 130) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Make emailSender and emailSubject nullable; add explicit hash columns
                // SQLite does not support ALTER COLUMN, so we recreate the table.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS email_receipt_sources_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        receiptId INTEGER NOT NULL,
                        emailSender TEXT,
                        emailSubject TEXT,
                        emailMessageId TEXT,
                        emailMessageIdHash TEXT,
                        contentFingerprintHash TEXT,
                        parsedAt INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        fingerprint TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO email_receipt_sources_new
                        (id, receiptId, emailSender, emailSubject, emailMessageId,
                         emailMessageIdHash, contentFingerprintHash, parsedAt, provider, confidence, fingerprint)
                    SELECT id, receiptId, emailSender, emailSubject, emailMessageId,
                           NULL, NULL, parsedAt, provider, confidence, fingerprint
                    FROM email_receipt_sources
                """.trimIndent())
                database.execSQL("DROP TABLE email_receipt_sources")
                database.execSQL("ALTER TABLE email_receipt_sources_new RENAME TO email_receipt_sources")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_receiptId ON email_receipt_sources(receiptId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageId ON email_receipt_sources(emailMessageId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_provider_parsedAt ON email_receipt_sources(provider, parsedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_parsedAt ON email_receipt_sources(parsedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_fingerprint ON email_receipt_sources(fingerprint)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageIdHash ON email_receipt_sources(emailMessageIdHash)")
            }
        }

        val MIGRATION_130_131 = object : androidx.room.migration.Migration(130, 131) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Backfill validDate for legacy rows that have validDate = 0.
                // Truncate lastUpdated to start-of-day (UTC) so that same-day
                // expenses occurring before the rate update time can still match.
                // Formula: (lastUpdated / 86400000) * 86400000
                database.execSQL("UPDATE exchange_rates SET validDate = (lastUpdated / 86400000) * 86400000 WHERE validDate = 0 AND lastUpdated > 0")
            }
        }

        // Migration 131 -> 132: Create entity_source_links table for provenance tracking
        val MIGRATION_131_132 = object : androidx.room.migration.Migration(131, 132) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS entity_source_links (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        targetEntityType TEXT NOT NULL,
                        targetEntityId INTEGER NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceEntityType TEXT NOT NULL,
                        sourceEntityLocalId INTEGER,
                        sourceIdentityKey TEXT NOT NULL,
                        externalIdHash TEXT,
                        externalFingerprintHash TEXT,
                        providerId TEXT,
                        accountIdHash TEXT,
                        operationRunId INTEGER,
                        importBatchId TEXT,
                        importRowNumber INTEGER,
                        linkRole TEXT NOT NULL,
                        linkStatus TEXT NOT NULL,
                        confidence REAL,
                        isPrimary INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        createdBy TEXT,
                        correlationId TEXT,
                        metadataJson TEXT,
                        metadataSchemaVersion INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_targetEntityType_targetEntityId
                    ON entity_source_links(targetEntityType, targetEntityId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_sourceType
                    ON entity_source_links(sourceType)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_sourceEntityType_sourceEntityLocalId
                    ON entity_source_links(sourceEntityType, sourceEntityLocalId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_sourceIdentityKey
                    ON entity_source_links(sourceIdentityKey)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_operationRunId
                    ON entity_source_links(operationRunId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_entity_source_links_correlationId
                    ON entity_source_links(correlationId)
                """.trimIndent())

                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_entity_source_links_targetEntityType_targetEntityId_sourceIdentityKey
                    ON entity_source_links(targetEntityType, targetEntityId, sourceIdentityKey)
                """.trimIndent())
            }
        }

        val MIGRATION_132_133 = object : androidx.room.migration.Migration(132, 133) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_intake (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT,
                        notificationKeyHash TEXT,
                        postTime INTEGER NOT NULL,
                        capturedAt INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        correlationId TEXT NOT NULL,
                        dedupeFingerprint TEXT NOT NULL,
                        contentHash TEXT,
                        title TEXT,
                        text TEXT,
                        bigText TEXT,
                        subText TEXT,
                        extrasJson TEXT,
                        rawStorageMode TEXT NOT NULL,
                        payloadMode TEXT NOT NULL,
                        rawPayloadPurgedAt INTEGER,
                        status TEXT NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0,
                        maxAttempts INTEGER NOT NULL DEFAULT 5,
                        nextAttemptAt INTEGER,
                        lockedAt INTEGER,
                        lockedBy TEXT,
                        lastAttemptAt INTEGER,
                        terminalAt INTEGER,
                        rawNotificationId INTEGER,
                        expenseId INTEGER,
                        pendingReviewId INTEGER,
                        lastFailureCode TEXT,
                        lastFailureMessageHash TEXT,
                        finalOutcome TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_notification_intake_dedupeFingerprint
                    ON notification_intake(dedupeFingerprint)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_intake_status_nextAttemptAt
                    ON notification_intake(status, nextAttemptAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_intake_status_updatedAt
                    ON notification_intake(status, updatedAt)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_intake_correlationId
                    ON notification_intake(correlationId)
                """.trimIndent())

                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_notification_intake_packageName_postTime
                    ON notification_intake(packageName, postTime)
                """.trimIndent())
            }
        }

        val MIGRATION_133_134 = object : androidx.room.migration.Migration(133, 134) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE notification_intake ADD COLUMN transientPayloadCiphertext TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE notification_intake ADD COLUMN transientPayloadNonce TEXT DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE notification_intake ADD COLUMN transientPayloadVersion INTEGER DEFAULT NULL"
                )
                database.execSQL(
                    "ALTER TABLE notification_intake ADD COLUMN transientPayloadPurgedAt INTEGER DEFAULT NULL"
                )
            }
        }

        val MIGRATION_134_135 = object : androidx.room.migration.Migration(134, 135) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P3-P1-10 / P3-NEW-10: Bank statement import run/item ledger tables.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bank_statement_import_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        statementReceiptId INTEGER,
                        sourceFingerprint TEXT,
                        correlationId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        totalItems INTEGER NOT NULL DEFAULT 0,
                        processedItems INTEGER NOT NULL DEFAULT 0,
                        createdReviewCount INTEGER NOT NULL DEFAULT 0,
                        duplicateExpenseCount INTEGER NOT NULL DEFAULT 0,
                        duplicatePendingCount INTEGER NOT NULL DEFAULT 0,
                        failedItemCount INTEGER NOT NULL DEFAULT 0,
                        pdfPartial INTEGER NOT NULL DEFAULT 0,
                        pagesProcessed INTEGER DEFAULT NULL,
                        totalPages INTEGER DEFAULT NULL,
                        errorSummary TEXT DEFAULT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_statement_import_runs_status ON bank_statement_import_runs (status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_statement_import_runs_startedAt ON bank_statement_import_runs (startedAt)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS bank_statement_import_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        runId INTEGER NOT NULL,
                        itemIndex INTEGER NOT NULL,
                        transactionFingerprint TEXT,
                        status TEXT NOT NULL,
                        duplicateReason TEXT DEFAULT NULL,
                        expenseId INTEGER,
                        pendingReviewId INTEGER,
                        merchant TEXT DEFAULT NULL,
                        amount REAL,
                        currency TEXT DEFAULT NULL,
                        transactionDate INTEGER,
                        errorReason TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_statement_import_items_runId ON bank_statement_import_items (runId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_bank_statement_import_items_transactionFingerprint ON bank_statement_import_items (transactionFingerprint)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_statement_import_items_runId_itemIndex ON bank_statement_import_items (runId, itemIndex)")
            }
        }

        val MIGRATION_135_136 = object : androidx.room.migration.Migration(135, 136) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P3-BLOCKER-001: Rebuild receipt_expense_links with FK clauses.
                // SQLite does not support ALTER TABLE ADD FOREIGN KEY, so we must
                // recreate the table. First clean orphans, then rebuild.
                database.beginTransaction()
                try {
                    // 1. Create new table with FKs
                    database.execSQL("""
                        CREATE TABLE receipt_expense_links_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            receiptId INTEGER NOT NULL,
                            expenseId INTEGER NOT NULL,
                            linkType TEXT NOT NULL,
                            confidence REAL,
                            source TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            createdBy TEXT,
                            isPrimary INTEGER NOT NULL DEFAULT 1,
                            metadata TEXT,
                            FOREIGN KEY(receiptId) REFERENCES scanned_receipts(id) ON DELETE CASCADE,
                            FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE CASCADE
                        )
                    """)

                    // 2. Copy valid rows (filter orphans)
                    database.execSQL("""
                        INSERT INTO receipt_expense_links_new
                            (id, receiptId, expenseId, linkType, confidence, source,
                             createdAt, createdBy, isPrimary, metadata)
                        SELECT id, receiptId, expenseId, linkType, confidence, source,
                               createdAt, createdBy, isPrimary, metadata
                        FROM receipt_expense_links
                        WHERE receiptId IN (SELECT id FROM scanned_receipts)
                          AND expenseId IN (SELECT id FROM expenses)
                    """)

                    // 3. Drop old table and rename
                    database.execSQL("DROP TABLE receipt_expense_links")
                    database.execSQL("ALTER TABLE receipt_expense_links_new RENAME TO receipt_expense_links")

                    // 4. Recreate indices
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_receiptId ON receipt_expense_links(receiptId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_expenseId ON receipt_expense_links(expenseId)")
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_receipt_expense_links_receipt_expense_unique ON receipt_expense_links(receiptId, expenseId)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_linkType ON receipt_expense_links(linkType)")
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_receipt_expense_links_createdAt ON receipt_expense_links(createdAt)")

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        val MIGRATION_136_137 = object : androidx.room.migration.Migration(136, 137) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P3-BLOCKER-009: Unique partial index on emailMessageIdHash.
                // Allows multiple nulls via WHERE clause.
                database.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_email_receipt_sources_emailMessageIdHash_unique
                    ON email_receipt_sources(emailMessageIdHash)
                    WHERE emailMessageIdHash IS NOT NULL AND emailMessageIdHash != ''
                """)
            }
        }

        val MIGRATION_137_138 = object : androidx.room.migration.Migration(137, 138) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P3-03EA-07: Clean duplicate fingerprints before creating unique indexes.
                // Keep the row with the smallest ID for each duplicate group; null the rest.
                val columns = listOf("imageHash" to "idx_scanned_receipts_imageHash_u",
                    "sourceFingerprint" to "idx_scanned_receipts_sourceFp_u",
                    "textFingerprint" to "idx_scanned_receipts_textFp_u",
                    "semanticFingerprint" to "idx_scanned_receipts_semFp_u")
                for ((col, _) in columns) {
                    database.execSQL("""
                        UPDATE scanned_receipts SET $col = NULL
                        WHERE id NOT IN (SELECT MIN(id) FROM scanned_receipts WHERE $col IS NOT NULL AND $col != '' GROUP BY $col)
                        AND $col IS NOT NULL AND $col != ''
                    """)
                }
                // Create unique partial indexes after cleanup
                for ((col, idxName) in columns) {
                    database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS $idxName ON scanned_receipts($col) WHERE $col IS NOT NULL AND $col != ''")
                }
            }
        }

        val MIGRATION_138_139 = object : androidx.room.migration.Migration(138, 139) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // PR 1 (P4-P1-01 / P4-NEW-02 / P4-NEW-08): Add reminder claim/recovery metadata columns.
                database.execSQL("ALTER TABLE recurring_reminder_deliveries ADD COLUMN claimedAt INTEGER")
                database.execSQL("ALTER TABLE recurring_reminder_deliveries ADD COLUMN lastAttemptAt INTEGER")
                database.execSQL("ALTER TABLE recurring_reminder_deliveries ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE recurring_reminder_deliveries ADD COLUMN failureReason TEXT")
                database.execSQL("ALTER TABLE recurring_reminder_deliveries ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_claimedAt
                    ON recurring_reminder_deliveries(claimedAt)
                """.trimIndent())
            }
        }

        val MIGRATION_139_140 = object : androidx.room.migration.Migration(139, 140) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P4-P1-05: Backfill occurrence keys to canonical sourceType-prefixed format.
                // Build canonical map for ALL rows (not just non-canonical) so that
                // already-canonical loser rows are also processed and deleted.

                // Step 1: Create canonical occurrence map for ALL rows
                database.execSQL("""
                    CREATE TEMP TABLE occ_canonical_map_139_140 (
                        occurrenceId INTEGER NOT NULL,
                        oldKey TEXT NOT NULL,
                        newKey TEXT NOT NULL,
                        canonicalId INTEGER NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO occ_canonical_map_139_140 (occurrenceId, oldKey, newKey, canonicalId)
                    SELECT ro.id,
                           ro.occurrenceKey,
                           ro.sourceType || '|' || ro.sourceId || '|' || ro.dueDate || '|' || ro.frequency,
                           (SELECT c.id FROM recurring_occurrences c
                            WHERE c.sourceType = ro.sourceType
                              AND c.sourceId = ro.sourceId
                              AND c.dueDate = ro.dueDate
                              AND c.frequency = ro.frequency
                            ORDER BY CASE
                                WHEN c.status = 'PAID' AND c.linkedExpenseId IS NOT NULL THEN 0
                                WHEN c.status = 'PAID' THEN 1
                                WHEN c.status IN ('CANCELLED','SKIPPED','MISSED','IGNORED') THEN 2
                                WHEN c.status = 'PLANNED' THEN 3
                                ELSE 4 END,
                                c.id
                            LIMIT 1)
                    FROM recurring_occurrences ro
                """.trimIndent())

                // Step 2: Dedupe losing reminder deliveries before remap
                database.execSQL("""
                    CREATE TEMP TABLE reminder_keep_139_140 AS
                    SELECT MIN(d.id) AS keepDeliveryId,
                           m.canonicalId AS targetOccurrenceId,
                           d.reminderWindow
                    FROM recurring_reminder_deliveries d
                    JOIN occ_canonical_map_139_140 m ON m.occurrenceId = d.occurrenceId
                    WHERE m.occurrenceId != m.canonicalId
                    GROUP BY m.canonicalId, d.reminderWindow
                """.trimIndent())

                database.execSQL("""
                    DELETE FROM recurring_reminder_deliveries
                    WHERE id IN (SELECT d.id FROM recurring_reminder_deliveries d
                                 JOIN occ_canonical_map_139_140 m ON m.occurrenceId = d.occurrenceId
                                 JOIN reminder_keep_139_140 k ON k.targetOccurrenceId = m.canonicalId
                                 AND k.reminderWindow = d.reminderWindow
                                 WHERE m.occurrenceId != m.canonicalId
                                   AND d.id != k.keepDeliveryId)
                """.trimIndent())

                // Delete losing reminder rows whose canonical occurrence already has same window
                database.execSQL("""
                    DELETE FROM recurring_reminder_deliveries
                    WHERE id IN (SELECT d.id FROM recurring_reminder_deliveries d
                                 JOIN occ_canonical_map_139_140 m ON m.occurrenceId = d.occurrenceId
                                 WHERE m.occurrenceId != m.canonicalId
                                   AND EXISTS (SELECT 1 FROM recurring_reminder_deliveries existing
                                               WHERE existing.occurrenceId = m.canonicalId
                                                 AND existing.reminderWindow = d.reminderWindow))
                """.trimIndent())

                // Step 3: Remap reminder deliveries to canonical occurrence (now safe)
                database.execSQL("""
                    UPDATE recurring_reminder_deliveries
                    SET occurrenceId = (SELECT m.canonicalId FROM occ_canonical_map_139_140 m
                                        WHERE m.occurrenceId = recurring_reminder_deliveries.occurrenceId)
                    WHERE occurrenceId IN (SELECT occurrenceId FROM occ_canonical_map_139_140
                                           WHERE occurrenceId != canonicalId)
                      AND NOT EXISTS (SELECT 1 FROM recurring_reminder_deliveries existing
                                      JOIN occ_canonical_map_139_140 m2
                                      ON m2.occurrenceId = recurring_reminder_deliveries.occurrenceId
                                      WHERE existing.occurrenceId = m2.canonicalId
                                        AND existing.reminderWindow = recurring_reminder_deliveries.reminderWindow)
                """.trimIndent())

                // Step 3: Delete conflicting losing reminder rows
                database.execSQL("""
                    DELETE FROM recurring_reminder_deliveries
                    WHERE occurrenceId IN (SELECT occurrenceId FROM occ_canonical_map_139_140
                                           WHERE occurrenceId != canonicalId)
                """.trimIndent())

                // Step 4: Remap lifecycle events to canonical occurrence
                database.execSQL("""
                    UPDATE recurring_lifecycle_events
                    SET occurrenceId = (SELECT m.canonicalId FROM occ_canonical_map_139_140 m
                                        WHERE m.occurrenceId = recurring_lifecycle_events.occurrenceId)
                    WHERE occurrenceId IN (SELECT occurrenceId FROM occ_canonical_map_139_140
                                           WHERE occurrenceId != canonicalId)
                """.trimIndent())

                // Step 5: Build planned target-key map including old AND already-new keys
                database.execSQL("""
                    CREATE TEMP TABLE planned_target_key_139_140 (
                        plannedId INTEGER NOT NULL,
                        oldSourceKey TEXT,
                        targetSourceKey TEXT NOT NULL
                    )
                """.trimIndent())

                database.execSQL("""
                    INSERT INTO planned_target_key_139_140 (plannedId, oldSourceKey, targetSourceKey)
                    SELECT p.id,
                           p.sourceOccurrenceKey,
                           COALESCE(
                               (SELECT m.newKey FROM occ_canonical_map_139_140 m
                                WHERE m.oldKey = p.sourceOccurrenceKey LIMIT 1),
                               p.sourceOccurrenceKey
                           )
                    FROM planned_expenses p
                    WHERE p.sourceOccurrenceKey IS NOT NULL
                """.trimIndent())

                // Step 6: Resolve duplicate planned open keys before update
                database.execSQL("""
                    CREATE TEMP TABLE planned_keep_139_140 AS
                    SELECT MIN(t.plannedId) AS keepId, t.targetSourceKey
                    FROM planned_target_key_139_140 t
                    JOIN planned_expenses p ON p.id = t.plannedId
                    WHERE p.status = 'PLANNED'
                    GROUP BY t.targetSourceKey
                """.trimIndent())

                // Cancel duplicate PLANNED rows
                database.execSQL("""
                    UPDATE planned_expenses
                    SET status = 'CANCELLED',
                        openSourceOccurrenceKey = NULL
                    WHERE status = 'PLANNED'
                      AND id IN (SELECT t.plannedId FROM planned_target_key_139_140 t
                                 JOIN planned_expenses p ON p.id = t.plannedId
                                 JOIN planned_keep_139_140 k ON k.targetSourceKey = t.targetSourceKey
                                 WHERE p.status = 'PLANNED'
                                   AND t.plannedId != k.keepId)
                """.trimIndent())

                // Step 7: Update planned source/open keys atomically
                database.execSQL("""
                    UPDATE planned_expenses
                    SET sourceOccurrenceKey = (SELECT t.targetSourceKey FROM planned_target_key_139_140 t
                                               WHERE t.plannedId = planned_expenses.id LIMIT 1),
                        openSourceOccurrenceKey = CASE
                            WHEN status = 'PLANNED' THEN (SELECT t.targetSourceKey FROM planned_target_key_139_140 t
                                                          WHERE t.plannedId = planned_expenses.id LIMIT 1)
                            ELSE NULL END
                    WHERE id IN (SELECT plannedId FROM planned_target_key_139_140)
                """.trimIndent())

                // Step 8: Enforce openSourceOccurrenceKey invariant
                database.execSQL("UPDATE planned_expenses SET openSourceOccurrenceKey = NULL WHERE status != 'PLANNED'")
                database.execSQL("""
                    UPDATE planned_expenses
                    SET openSourceOccurrenceKey = sourceOccurrenceKey
                    WHERE status = 'PLANNED'
                      AND sourceOccurrenceKey IS NOT NULL
                      AND openSourceOccurrenceKey IS NULL
                """.trimIndent())

                // Step 9: Delete losing duplicate occurrences (after all dependents remapped)
                database.execSQL("""
                    DELETE FROM recurring_occurrences
                    WHERE id IN (SELECT occurrenceId FROM occ_canonical_map_139_140
                                 WHERE occurrenceId != canonicalId)
                """.trimIndent())

                // Step 10: Now safe — update canonical occurrence keys
                database.execSQL("""
                    UPDATE recurring_occurrences
                    SET occurrenceKey = sourceType || '|' || sourceId || '|' || dueDate || '|' || frequency
                    WHERE occurrenceKey != sourceType || '|' || sourceId || '|' || dueDate || '|' || frequency
                """.trimIndent())

                // Cleanup temp tables
                database.execSQL("DROP TABLE IF EXISTS occ_canonical_map_139_140")
                database.execSQL("DROP TABLE IF EXISTS planned_target_key_139_140")
                database.execSQL("DROP TABLE IF EXISTS planned_keep_139_140")
                database.execSQL("DROP TABLE IF EXISTS reminder_keep_139_140")
            }
        }

        val MIGRATION_140_141 = object : androidx.room.migration.Migration(140, 141) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // P4-NEW-11: Add FK on recurring_reminder_deliveries.occurrenceId -> recurring_occurrences.id
                // Rebuild the table to add the FK constraint (SQLite cannot ALTER TABLE ADD FK).
                database.execSQL("PRAGMA foreign_keys=OFF")
                try {
                    database.execSQL("""
                        CREATE TABLE recurring_reminder_deliveries_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            occurrenceId INTEGER NOT NULL,
                            reminderWindow TEXT NOT NULL,
                            scheduledAt INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            lastSentAt INTEGER,
                            dismissedAt INTEGER,
                            snoozedUntil INTEGER,
                            notificationId INTEGER,
                            createdAt INTEGER NOT NULL,
                            claimedAt INTEGER,
                            lastAttemptAt INTEGER,
                            attemptCount INTEGER NOT NULL DEFAULT 0,
                            failureReason TEXT,
                            updatedAt INTEGER NOT NULL DEFAULT 0,
                            FOREIGN KEY(occurrenceId) REFERENCES recurring_occurrences(id) ON DELETE CASCADE
                        )
                    """.trimIndent())

                    database.execSQL("""
                        INSERT INTO recurring_reminder_deliveries_new (
                            id, occurrenceId, reminderWindow, scheduledAt, status,
                            lastSentAt, dismissedAt, snoozedUntil, notificationId, createdAt,
                            claimedAt, lastAttemptAt, attemptCount, failureReason, updatedAt
                        )
                        SELECT
                            d.id, d.occurrenceId, d.reminderWindow, d.scheduledAt, d.status,
                            d.lastSentAt, d.dismissedAt, d.snoozedUntil, d.notificationId, d.createdAt,
                            d.claimedAt, d.lastAttemptAt, d.attemptCount, d.failureReason, d.updatedAt
                        FROM recurring_reminder_deliveries d
                        INNER JOIN recurring_occurrences o ON o.id = d.occurrenceId
                    """.trimIndent())

                    database.execSQL("DROP TABLE recurring_reminder_deliveries")
                    database.execSQL("ALTER TABLE recurring_reminder_deliveries_new RENAME TO recurring_reminder_deliveries")

                    database.execSQL("""
                        CREATE UNIQUE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_occurrenceId_reminderWindow
                        ON recurring_reminder_deliveries(occurrenceId, reminderWindow)
                    """.trimIndent())
                    database.execSQL("""
                        CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_status
                        ON recurring_reminder_deliveries(status)
                    """.trimIndent())
                    database.execSQL("""
                        CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_scheduledAt
                        ON recurring_reminder_deliveries(scheduledAt)
                    """.trimIndent())
                    database.execSQL("""
                        CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_claimedAt
                        ON recurring_reminder_deliveries(claimedAt)
                    """.trimIndent())
                    database.execSQL("""
                        CREATE INDEX IF NOT EXISTS index_recurring_reminder_deliveries_occurrenceId
                        ON recurring_reminder_deliveries(occurrenceId)
                    """.trimIndent())
                } finally {
                    database.execSQL("PRAGMA foreign_keys=ON")
                }
            }
        }

        // Migration 141 -> 142: Combined change to budget_forecasts (Pipeline 6).
        //   (a) P6-CURRENT-010: add four data-quality columns
        //       (isPartial, excludedExpenseCount, qualityWarningsJson, rateBasis).
        //   (b) P6-CURRENT-005 / P6-P1-15: relax the budgetId -> budgets(id) foreign
        //       key from ON DELETE RESTRICT to ON DELETE CASCADE (Option A: CASCADE-purge).
        // SQLite cannot ALTER/DROP a foreign key in place, so the FK change forces a
        // full table-recreate; the four new columns are therefore added in the
        // recreated table's CREATE TABLE (not via a separate ALTER). Existing rows are
        // copied with an explicit column list; the new columns take their defaults
        // (isPartial=0, excludedExpenseCount=0, qualityWarningsJson=NULL, rateBasis=NULL).
        // Mirrors the table-recreate pattern of MIGRATION_29_30.
        val MIGRATION_141_142 = object : androidx.room.migration.Migration(141, 142) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.beginTransaction()
                try {
                    // 1. Create the recreated table = all existing columns (exact names,
                    //    affinities, notnull, defaults from schema 141) PLUS the four new
                    //    data-quality columns, with the FK relaxed to ON DELETE CASCADE.
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS budget_forecasts_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            budgetId INTEGER NOT NULL,
                            forecastDate INTEGER NOT NULL,
                            targetPeriodStart INTEGER NOT NULL,
                            targetPeriodEnd INTEGER NOT NULL,
                            predictedSpending REAL NOT NULL,
                            predictedRemaining REAL NOT NULL,
                            confidenceScore REAL NOT NULL,
                            riskLevel TEXT NOT NULL,
                            overspendProbability REAL NOT NULL,
                            recommendationsJson TEXT,
                            actualSpending REAL,
                            forecastAccuracy REAL,
                            currency TEXT NOT NULL DEFAULT 'EUR',
                            isActive INTEGER NOT NULL DEFAULT 1,
                            createdAt INTEGER NOT NULL,
                            isPartial INTEGER NOT NULL DEFAULT 0,
                            excludedExpenseCount INTEGER NOT NULL DEFAULT 0,
                            qualityWarningsJson TEXT,
                            rateBasis TEXT,
                            FOREIGN KEY(budgetId) REFERENCES budgets(id) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    // 2. Copy every existing row using an EXPLICIT column list (no SELECT *).
                    //    The four new columns are omitted so they take their defaults.
                    database.execSQL(
                        """
                        INSERT INTO budget_forecasts_new (
                            id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                            predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                            overspendProbability, recommendationsJson, actualSpending,
                            forecastAccuracy, currency, isActive, createdAt
                        )
                        SELECT
                            id, budgetId, forecastDate, targetPeriodStart, targetPeriodEnd,
                            predictedSpending, predictedRemaining, confidenceScore, riskLevel,
                            overspendProbability, recommendationsJson, actualSpending,
                            forecastAccuracy, currency, isActive, createdAt
                        FROM budget_forecasts
                        """.trimIndent()
                    )

                    // 3. Drop the old table.
                    database.execSQL("DROP TABLE budget_forecasts")

                    // 4. Rename the recreated table into place.
                    database.execSQL("ALTER TABLE budget_forecasts_new RENAME TO budget_forecasts")

                    // 5. Recreate ALL FOUR indices with the EXACT names Room expects
                    //    (three non-unique + the UNIQUE composite). Room validates index
                    //    names strictly.
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_budgetId " +
                        "ON budget_forecasts (budgetId)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_forecastDate " +
                        "ON budget_forecasts (forecastDate)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_budget_forecasts_isActive " +
                        "ON budget_forecasts (isActive)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_budget_forecasts_budgetId_targetPeriodStart_forecastDate " +
                        "ON budget_forecasts (budgetId, targetPeriodStart, forecastDate)"
                    )

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 142 -> 143: S9 / P9-P1-09 (PR6). Add the durable, claim-before-notify
        // sent-state table for WarrantyExpirationWorker, replacing the SharedPreferences-based
        // dedup (warranty_expiration_worker_prefs).
        //
        // ADDITIVE ONLY: creates a brand-new table + its indices. No existing table is
        // dropped or altered, so this is non-destructive and fallbackToDestructiveMigration
        // stays OFF.
        //
        // E6 guidance: the CREATE TABLE uses an EXPLICIT column list (no SELECT *) whose
        // column names / affinities / NOT NULL / nullability EXACTLY match what Room
        // generates from [WarrantyReminderDelivery]. Per Room codegen, columns WITHOUT an
        // @ColumnInfo(defaultValue=...) are emitted with NO SQL DEFAULT clause (a Kotlin
        // constructor default such as `= 0L` does NOT produce a SQL DEFAULT). The entity
        // declares no @ColumnInfo defaults, so this CREATE TABLE deliberately omits all
        // DEFAULT clauses — matching Room's expected schema (a stray DEFAULT would fail
        // Room's open-time schema validation).
        //
        // Column order follows the entity's declaration order exactly. The FK mirrors the
        // entity: warrantyId -> warranties(id) ON DELETE CASCADE (warranties PK is `id`).
        // Index names match Room's generated names:
        //   - UNIQUE index_warranty_reminder_deliveries_warrantyId_windowDays_expiryDate
        //   - index_warranty_reminder_deliveries_warrantyId  (required FK child index)
        val MIGRATION_142_143 = object : androidx.room.migration.Migration(142, 143) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS warranty_reminder_deliveries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        warrantyId INTEGER NOT NULL,
                        windowDays INTEGER NOT NULL,
                        expiryDate INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        claimedAt INTEGER,
                        lastAttemptAt INTEGER,
                        attemptCount INTEGER NOT NULL,
                        notificationId INTEGER,
                        failureReason TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(warrantyId) REFERENCES warranties(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // Idempotency key: one delivery per (warrantyId, windowDays, expiryDate).
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_warranty_reminder_deliveries_warrantyId_windowDays_expiryDate " +
                        "ON warranty_reminder_deliveries (warrantyId, windowDays, expiryDate)"
                )

                // Required index on the FK child column (Room enforces this).
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_warranty_reminder_deliveries_warrantyId " +
                        "ON warranty_reminder_deliveries (warrantyId)"
                )

                // Normalize raw_notifications by rebuilding the table to match Room entity exactly.
                // Index/column drift from 20+ version jumps causes validation failure even when
                // all individual migrations succeed. Full table rebuild preserves all data.
                database.execSQL("""
                    CREATE TABLE raw_notifications_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        appName TEXT,
                        title TEXT,
                        text TEXT,
                        bigText TEXT,
                        subText TEXT,
                        extrasJson TEXT,
                        timestamp INTEGER NOT NULL,
                        capturedAt INTEGER NOT NULL,
                        isProcessed INTEGER NOT NULL,
                        isRelevant INTEGER,
                        parseResult TEXT,
                        rawContentPurgedAt INTEGER,
                        dedupeFingerprint TEXT
                    )
                """.trimIndent())
                database.execSQL(
                    "INSERT INTO raw_notifications_new SELECT id, packageName, appName, title, text, " +
                    "bigText, subText, extrasJson, timestamp, capturedAt, isProcessed, isRelevant, " +
                    "parseResult, rawContentPurgedAt, dedupeFingerprint FROM raw_notifications"
                )
                database.execSQL("DROP TABLE raw_notifications")
                database.execSQL("ALTER TABLE raw_notifications_new RENAME TO raw_notifications")
                // Recreate all 5 indices matching Room @Entity declaration exactly
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp ON raw_notifications (packageName, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_capturedAt ON raw_notifications (capturedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_isRelevant ON raw_notifications (isRelevant)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text_bigText ON raw_notifications (packageName, timestamp, title, text, bigText)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedupeFingerprint ON raw_notifications (dedupeFingerprint)")
            }
        }

        val MIGRATION_143_144 = object : androidx.room.migration.Migration(143, 144) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("DROP INDEX IF EXISTS index_categories_name_nocase")
            }
        }

        val MIGRATION_144_145 = object : androidx.room.migration.Migration(144, 145) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Rebuild pending_reviews to match Room entity (nullable suggestedAmount, no CHECK constraints)
                database.execSQL("PRAGMA foreign_keys=OFF")
                database.execSQL("DROP TABLE IF EXISTS pending_reviews")
                database.execSQL("""
                    CREATE TABLE pending_reviews (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        rawNotificationId INTEGER,
                        scannedReceiptId INTEGER,
                        suggestedAmount REAL,
                        suggestedCurrency TEXT NOT NULL,
                        suggestedMerchant TEXT NOT NULL,
                        suggestedMerchantKey TEXT,
                        suggestedType TEXT NOT NULL,
                        suggestedCategoryId INTEGER,
                        suggestedDate INTEGER,
                        confidence REAL NOT NULL,
                        matchType TEXT,
                        explanation TEXT,
                        packageName TEXT NOT NULL,
                        notificationTitle TEXT,
                        notificationText TEXT,
                        createdAt INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'PENDING',
                        suggestedDirection TEXT,
                        suggestedAccountName TEXT,
                        suggestedLatitude REAL,
                        suggestedLongitude REAL,
                        extractionState TEXT NOT NULL DEFAULT 'REAL_EXTRACTION',
                        FOREIGN KEY(rawNotificationId) REFERENCES raw_notifications(id) ON UPDATE NO ACTION ON DELETE SET NULL,
                        FOREIGN KEY(scannedReceiptId) REFERENCES scanned_receipts(id) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_scannedReceiptId ON pending_reviews (scannedReceiptId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status ON pending_reviews (status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_createdAt ON pending_reviews (status, createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_suggestedMerchantKey ON pending_reviews (suggestedMerchantKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_status_suggestedMerchantKey_suggestedDate ON pending_reviews (status, suggestedMerchantKey, suggestedDate)")

                // Add missing budget column from rescue-created v144 DB
                var has = false
                database.query("PRAGMA table_info(`budgets`)").use { c ->
                    val ni = c.getColumnIndex("name")
                    while (c.moveToNext()) {
                        if (c.getString(ni) == "rolloverDeficitTracking") { has = true; break }
                    }
                }
                if (!has) database.execSQL("ALTER TABLE budgets ADD COLUMN rolloverDeficitTracking INTEGER NOT NULL DEFAULT 0")
                // Drop extra fresh-install indices that Room entity doesn't declare
                database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_nonnull")
                database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_both_null")
                database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_title_null")
                database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_dedup_text_null")
                database.execSQL("DROP INDEX IF EXISTS index_raw_notifications_packageName_timestamp_title_text")
                database.execSQL("DROP INDEX IF EXISTS index_categories_name_nocase")
                // Recreate only the 5 indices Room entity declares
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp ON raw_notifications (packageName, timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_capturedAt ON raw_notifications (capturedAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_isRelevant ON raw_notifications (isRelevant)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notifications_packageName_timestamp_title_text_bigText ON raw_notifications (packageName, timestamp, title, text, bigText)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notifications_dedupeFingerprint ON raw_notifications (dedupeFingerprint)")
            }
        }

        /**
         * Creates a file-backed [RoomDatabase.Builder] pre-configured with
         * [FRESH_INSTALL_CALLBACK] and the full migration chain.
         *
         * Every test that needs a fresh `AppDatabase` **must** go through this
         * factory so that supplementary indexes (Batch 3 through Batch 8) are
         * present, matching the production fresh-install path.
         */
        @JvmStatic
        fun fileBuilder(
            context: android.content.Context,
            databaseName: String = DATABASE_NAME
        ): androidx.room.RoomDatabase.Builder<AppDatabase> {
            return configureBuilder(
                Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            )
        }

        @JvmStatic
        fun inMemoryBuilder(context: android.content.Context): androidx.room.RoomDatabase.Builder<AppDatabase> {
            return configureBuilder(
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            )
                .allowMainThreadQueries()
        }

        private fun configureBuilder(
            builder: androidx.room.RoomDatabase.Builder<AppDatabase>
        ): androidx.room.RoomDatabase.Builder<AppDatabase> {
            return builder
                .addMigrations(*ALL_MIGRATIONS)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        }

        /**
         * Canonical migration registry used by every database builder path.
         *
         * Keeping this list centralized prevents subtle drift where one code path
         * forgets to register a migration (which can surface as upgrade/downgrade
         * crashes on startup depending on the on-device schema version).
         */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = DatabaseMigrations.ALL
    }
}
