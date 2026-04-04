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
        EmailReceiptSource::class
    ],
    version = 70,
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

    companion object {
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
                    
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
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
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
                }
            }
        }

        // Migration 49 -> 50: Comprehensive schema fix for all tables with default value mismatches
        // This migration recreates tables with proper DEFAULT constraints to align with @ColumnInfo annotations
        val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                try {
                    database.beginTransaction()

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
                    
                    database.execSQL("""
                        INSERT INTO expenses_new SELECT * FROM expenses
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
                    database.execSQL("CREATE INDEX IF NOT EXISTS index_pending_reviews_rawNotificationId ON pending_reviews (rawNotificationId)")
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
                    
                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
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
                        emailMessageId TEXT,
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

                    database.setTransactionSuccessful()
                } finally {
                    database.endTransaction()
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
                                emailMessageId TEXT,
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

                if (exists && oldColumns.containsAll(canonicalColumns.toSet())) {
                    val columnList = canonicalColumns.joinToString(", ") { "`$it`" }
                    database.execSQL(
                        "INSERT INTO `$tableName` ($columnList) " +
                            "SELECT $columnList FROM `$tempName`"
                    )
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
                    database.execSQL("UPDATE pending_reviews SET suggestedMerchantKey = LOWER(REPLACE(REPLACE(REPLACE(suggestedMerchant, '.', ''), '''', ''), ' ', '')) WHERE suggestedMerchantKey IS NULL")
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

                            val encryptedAccess = BankTokenCipher.encryptIfNeeded(access)
                            val encryptedRefresh = BankTokenCipher.encryptIfNeeded(refresh)

                            val shouldMarkEncrypted = encryptedAccess != null || encryptedRefresh != null
                            val targetVersion = if (shouldMarkEncrypted) 1 else 0

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
                            emailMessageId TEXT,
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

        /**
         * Canonical migration registry used by every database builder path.
         *
         * Keeping this list centralized prevents subtle drift where one code path
         * forgets to register a migration (which can surface as upgrade/downgrade
         * crashes on startup depending on the on-device schema version).
         */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
            MIGRATION_46_47,
            MIGRATION_47_48,
            MIGRATION_48_49,
            MIGRATION_49_50,
            MIGRATION_50_51,
            MIGRATION_51_52,
            MIGRATION_52_53,
            MIGRATION_53_54,
            MIGRATION_54_55,
            MIGRATION_55_56,
            MIGRATION_56_57,
            MIGRATION_57_58,
            MIGRATION_58_59,
            MIGRATION_59_60,
            MIGRATION_60_61,
            MIGRATION_61_62,
            MIGRATION_62_63,
            MIGRATION_63_64,
            MIGRATION_64_65,
            MIGRATION_65_66,
            MIGRATION_66_67,
            MIGRATION_67_68,
            MIGRATION_68_69,
            MIGRATION_69_70
        )
    }
}
