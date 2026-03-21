package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.dao.*
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.AiArtifactEntity
import com.yourname.expensetracker.data.database.dao.AiArtifactDao

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
        RecommendationEntity::class
    ],
        version = 36,
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
    abstract fun plannedExpenseDao(): PlannedExpenseDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun merchantNormalizationDao(): MerchantNormalizationDao
    abstract fun merchantLocationDao(): MerchantLocationDao
    abstract fun aiArtifactDao(): AiArtifactDao
    abstract fun aiChatSessionDao(): AiChatSessionDao
    abstract fun aiChatMessageDao(): AiChatMessageDao
    abstract fun recommendationDao(): RecommendationDao

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
    }
}
