# 3 Data Layer

## Table of Contents
1. [app\src\main\java\com\yourname\expensetracker\data\database\AppDatabase.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseappdatabasekt)
2. [app\src\main\java\com\yourname\expensetracker\data\database\converter\Converters.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt)
3. [app\src\main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt)
4. [app\src\main\java\com\yourname\expensetracker\data\database\dao\BudgetDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaobudgetdaokt)
5. [app\src\main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt)
6. [app\src\main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt)
7. [app\src\main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt)
8. [app\src\main\java\com\yourname\expensetracker\data\database\dao\MerchantNormalizationDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaomerchantnormalizationdaokt)
9. [app\src\main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt)
10. [app\src\main\java\com\yourname\expensetracker\data\database\dao\PlannedExpenseDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaoplannedexpensedaokt)
11. [app\src\main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt)
12. [app\src\main\java\com\yourname\expensetracker\data\database\dao\RecurringExpenseDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaorecurringexpensedaokt)
13. [app\src\main\java\com\yourname\expensetracker\data\database\dao\SavingsGoalDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaosavingsgoaldaokt)
14. [app\src\main\java\com\yourname\expensetracker\data\database\dao\ScannedReceiptDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaoscannedreceiptdaokt)
15. [app\src\main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt)
16. [app\src\main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt)
17. [app\src\main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt)
18. [app\src\main\java\com\yourname\expensetracker\data\database\entity\Budget.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt)
19. [app\src\main\java\com\yourname\expensetracker\data\database\entity\Category.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitycategorykt)
20. [app\src\main\java\com\yourname\expensetracker\data\database\entity\Expense.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityexpensekt)
21. [app\src\main\java\com\yourname\expensetracker\data\database\entity\ManualRecurringExpense.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymanualrecurringexpensekt)
22. [app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantAlias.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantaliaskt)
23. [app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantCanonical.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantcanonicalkt)
24. [app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt)
25. [app\src\main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt)
26. [app\src\main\java\com\yourname\expensetracker\data\database\entity\PlannedExpense.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityplannedexpensekt)
27. [app\src\main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt)
28. [app\src\main\java\com\yourname\expensetracker\data\database\entity\SavingsGoal.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitysavingsgoalkt)
29. [app\src\main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt)
30. [app\src\main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt)
31. [app\src\main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt)
32. [app\src\main\java\com\yourname\expensetracker\data\database\model\DashboardWidgetConfig.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasemodeldashboardwidgetconfigkt)
33. [app\src\main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategorykt)
34. [app\src\main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory_Extensions.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategory_extensionskt)
35. [app\src\main\java\com\yourname\expensetracker\data\database\model\PendingReviewWithReceipt.kt](#appsrcmainjavacomyournameexpensetrackerdatadatabasemodelpendingreviewwithreceiptkt)
36. [app\src\main\java\com\yourname\expensetracker\data\provider\MerchantCategoryProvider.kt](#appsrcmainjavacomyournameexpensetrackerdataprovidermerchantcategoryproviderkt)
37. [app\src\main\java\com\yourname\expensetracker\data\repository\AnalyticsRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositoryanalyticsrepositorykt)
38. [app\src\main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt)
39. [app\src\main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt)
40. [app\src\main\java\com\yourname\expensetracker\data\repository\DashboardRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositorydashboardrepositorykt)
41. [app\src\main\java\com\yourname\expensetracker\data\repository\FinancialWeatherRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositoryfinancialweatherrepositorykt)
42. [app\src\main\java\com\yourname\expensetracker\data\repository\MerchantCategoryRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositorymerchantcategoryrepositorykt)
43. [app\src\main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt)
44. [app\src\main\java\com\yourname\expensetracker\data\repository\PlannedExpenseRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositoryplannedexpenserepositorykt)
45. [app\src\main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt](#appsrcmainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt)

---

## app\src\main\java\com\yourname\expensetracker\data\database\AppDatabase.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseappdatabasekt"></a>
```kotlin
package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.dao.*
import androidx.room.*

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
        MerchantAlias::class
    ],
    version = 20,
    exportSchema = false
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
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\converter\Converters.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.converter

import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import androidx.room.TypeConverter
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PaymentMethod

class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String {
        return value.name
    }

    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }

    @TypeConverter
    fun fromPaymentMethod(value: PaymentMethod): String {
        return value.name
    }

    @TypeConverter
    fun toPaymentMethod(value: String): PaymentMethod {
        return try {
            PaymentMethod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            PaymentMethod.UNKNOWN
        }
    }

    @TypeConverter
    fun fromBudgetPeriod(value: com.yourname.expensetracker.data.database.entity.BudgetPeriod): String {
        return value.name
    }

    @TypeConverter
    fun toBudgetPeriod(value: String): com.yourname.expensetracker.data.database.entity.BudgetPeriod {
        return try {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.valueOf(value)
        } catch (e: IllegalArgumentException) {
            com.yourname.expensetracker.data.database.entity.BudgetPeriod.MONTHLY
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPackageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(blockedPackage: BlockedPackage)

    @Delete
    suspend fun unblock(blockedPackage: BlockedPackage)

    @Query("DELETE FROM blocked_packages WHERE packageName = :packageName")
    suspend fun unblock(packageName: String)

    @Query("SELECT * FROM blocked_packages")
    fun getAllFlow(): Flow<List<BlockedPackage>>

    @Query("SELECT packageName FROM blocked_packages")
    fun getAllPackageNamesFlow(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_packages WHERE packageName = :packageName)")
    suspend fun isBlocked(packageName: String): Boolean

    @Query("DELETE FROM blocked_packages")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\BudgetDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaobudgetdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long

    @Update
    suspend fun update(budget: Budget)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun getById(id: Long): Budget?

    @Query("SELECT * FROM budgets")
    suspend fun getAll(): List<Budget>

    @Query("SELECT * FROM budgets")
    fun getAllFlow(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    suspend fun getActiveBudgets(): List<Budget>

    @Query("SELECT * FROM budgets WHERE isActive = 1")
    fun getActiveBudgetsFlow(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE categoryId IS NULL AND isActive = 1 LIMIT 1")
    suspend fun getOverallBudget(): Budget?

    @Query("SELECT * FROM budgets WHERE categoryId = :categoryId AND isActive = 1 LIMIT 1")
    suspend fun getByCategory(categoryId: Long): Budget?

    @Query("UPDATE budgets SET lastWarningNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateWarningNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastCriticalNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateCriticalNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET lastExceededNotifiedAt = :timestamp WHERE id = :id")
    suspend fun updateExceededNotification(id: Long, timestamp: Long)

    @Query("UPDATE budgets SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Category>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)

    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<Category>
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Expense>>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit")
    fun getAllWithCategoryFlow(limit: Int = 200): Flow<List<ExpenseWithCategory>>

    @Transaction
    @Query("SELECT * FROM expenses ORDER BY date DESC LIMIT :limit OFFSET :offset")
    suspend fun getExpensesWithCategoryPaged(limit: Int, offset: Int): List<ExpenseWithCategory>

    @Transaction
    @Query("""
        SELECT * FROM expenses 
        WHERE date >= :startMs AND date <= :endMs 
        AND (:type IS NULL OR transactionType = :type)
        AND (:categoryId IS NULL OR categoryId = :categoryId)
        AND (:merchant IS NULL OR merchant = :merchant)
        ORDER BY date DESC
    """)
    fun getExpensesWithCategoryFilteredFlow(
        startMs: Long, 
        endMs: Long, 
        type: String?,
        categoryId: Long?, 
        merchant: String?
    ): Flow<List<ExpenseWithCategory>>

    @Transaction
    @Query("SELECT * FROM expenses WHERE date >= :startMs AND date <= :endMs ORDER BY date DESC")
    fun getExpensesWithCategoryInPeriodFlow(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>>

    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAll(): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :since ORDER BY date DESC")
    suspend fun getExpensesSince(since: Long): List<Expense>

    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>

    @Query("DELETE FROM expenses")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(expense: Expense)

    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)

    @Query("UPDATE expenses SET merchant = :merchant WHERE id = :expenseId")
    suspend fun updateMerchant(expenseId: Long, merchant: String)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE transactionType = 'PURCHASE'
            AND ABS(amount - :amount) < 0.01
            AND ABS(date - :date) <= :windowMs
            AND (
                -- Exact match
                merchant = :merchant 
                OR 
                -- Case-insensitive match
                UPPER(merchant) = UPPER(:merchant)
                OR
                -- Normalized match (remove spaces)
                UPPER(REPLACE(merchant, ' ', '')) = UPPER(REPLACE(:merchant, ' ', ''))
                OR
                -- Substring match
                merchant LIKE '%' || :merchant || '%'
                OR
                :merchant LIKE '%' || merchant || '%'
            )
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCategorySpentInPeriod(categoryId: Long, startMs: Long, endMs: Long): Double

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND categoryId = :categoryId 
        AND date >= :startMs AND date < :endMs
    """)
    fun getCategorySpentInPeriodFlow(categoryId: Long, startMs: Long, endMs: Long): Flow<Double>

    // === Merchant Search for Manual Entry ===
    @Query("""
        SELECT merchant, categoryId, AVG(amount) as avgAmount, COUNT(*) as txCount
        FROM expenses
        WHERE UPPER(merchant) LIKE '%' || UPPER(:query) || '%'
        GROUP BY UPPER(merchant)
        ORDER BY txCount DESC
        LIMIT 10
    """)
    suspend fun searchMerchants(query: String): List<MerchantSuggestion>

    @Query("""
        SELECT DISTINCT merchant
        FROM expenses
        ORDER BY date DESC
        LIMIT 100
    """)
    suspend fun getRecentMerchantNames(): List<String>

    // === Analytics Queries ===

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesByTypeBetween(startDate: Long, endDate: Long, type: String): List<Expense>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>

    @Query("SELECT * FROM expenses WHERE transactionType = :type AND date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesByTypeBetweenFlow(startDate: Long, endDate: Long, type: String): Flow<List<Expense>>

    @Query("""
        SELECT SUM(amount) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?

    @Query("""
        SELECT merchant, SUM(amount) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        GROUP BY UPPER(merchant)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>

    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>

    @Query("SELECT COUNT(*) FROM expenses WHERE transactionType = 'PURCHASE'")
    suspend fun getPurchaseCount(): Int

    @Query("SELECT MIN(date) FROM expenses")
    suspend fun getOldestExpenseDate(): Long?

    // === Tier 1 & 2 Analytics Queries ===

    // Monthly total for a specific month range
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getTotalForPeriod(startMs: Long, endMs: Long): Double

    // Count for a period
    @Query("""
        SELECT COUNT(*) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
    """)
    suspend fun getCountForPeriod(startMs: Long, endMs: Long): Int

    // Category totals for a period
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsForPeriod(startMs: Long, endMs: Long): List<CategoryTotal>

    // Merchant averages (merchants with 2+ transactions)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING transactionCount >= 2
        ORDER BY totalAmount DESC
    """)
    suspend fun getMerchantStats(): List<MerchantStats>

    // All merchant stats (including single-transaction merchants)
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        ORDER BY totalAmount DESC
    """)
    suspend fun getAllMerchantStats(): List<MerchantStats>

    // Top merchants by total spending for a period
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY merchant
        ORDER BY totalAmount DESC
        LIMIT :limit
    """)
    suspend fun getTopMerchantsForPeriod(startMs: Long, endMs: Long, limit: Int = 10): List<MerchantStats>

    // Largest single transaction in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForPeriod(startMs: Long, endMs: Long): Expense?

    // Largest single transaction for a specific merchant in a period
    @Query("""
        SELECT * FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        AND merchant = :merchant
        ORDER BY amount DESC
        LIMIT 1
    """)
    suspend fun getLargestExpenseForMerchant(merchant: String, startMs: Long, endMs: Long): Expense?

    // Daily spending totals for a period (for pace calculation)
    @Query("""
        SELECT (date / 86400000) as dayEpoch, SUM(amount) as total, COUNT(*) as txCount
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startMs AND date < :endMs
        GROUP BY dayEpoch
        ORDER BY dayEpoch ASC
    """)
    suspend fun getDailyTotalsForPeriod(startMs: Long, endMs: Long): List<DailyTotal>

    // Recurring candidates: merchants that appear in multiple distinct months
    @Query("""
        SELECT merchant as merchantName, 
               SUM(amount) as totalAmount,
               COUNT(*) as transactionCount,
               AVG(amount) as averageAmount,
               MIN(amount) as minAmount,
               MAX(amount) as maxAmount,
               MIN(date) as firstDate, 
               MAX(date) as lastDate
        FROM expenses 
        WHERE transactionType = 'PURCHASE'
        GROUP BY merchant
        HAVING transactionCount >= 2 
        AND (maxAmount - minAmount) < (averageAmount * 0.15)
        ORDER BY transactionCount DESC
    """)
    suspend fun getRecurringCandidates(): List<MerchantStats>

    // Day-of-week spending pattern
    @Query("""
        SELECT 
            CAST((((date + :timeZoneOffset) / 1000 + 259200) % 604800) / 86400 AS INTEGER) as dayOfWeek,
            SUM(amount) as total,
            COUNT(*) as txCount,
            AVG(amount) as avgAmount
        FROM expenses
        WHERE transactionType = 'PURCHASE'
        AND date >= :startMs AND date < :endMs
        GROUP BY dayOfWeek
        ORDER BY dayOfWeek ASC
    """)
    suspend fun getDayOfWeekPattern(startMs: Long, endMs: Long, timeZoneOffset: Int): List<DayOfWeekTotal>
}

data class MerchantSuggestion(
    val merchant: String,
    val categoryId: Long?,
    val avgAmount: Double,
    val txCount: Int
)

data class MerchantTotal(
    val merchant: String,
    val total: Double,
    val cnt: Int
)

data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val txCount: Int = 0 
)

data class MerchantStats(
    val merchantName: String,
    val totalAmount: Double,
    val transactionCount: Int,
    val averageAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val firstDate: Long,
    val lastDate: Long
)

data class DailyTotal(
    val dayEpoch: Long,
    val total: Double,
    val txCount: Int
)

data class DayOfWeekTotal(
    val dayOfWeek: Int,
    val total: Double,
    val txCount: Int,
    val avgAmount: Double
)


```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.MerchantCategory

@Dao
interface MerchantCategoryDao {
    @Query("SELECT * FROM merchant_categories WHERE merchantPattern = :merchantPattern")
    suspend fun getCategoryForMerchant(merchantPattern: String): MerchantCategory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchantCategory: MerchantCategory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(merchantCategories: List<MerchantCategory>)

    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategory>

    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\MerchantNormalizationDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaomerchantnormalizationdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.MerchantAlias
import com.yourname.expensetracker.data.database.entity.MerchantCanonical

/**
 * DAO for merchant normalization tables.
 */
@Dao
interface MerchantNormalizationDao {

    // ==================== Canonical Merchants ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCanonical(merchant: MerchantCanonical): Long

    @Update
    suspend fun updateCanonical(merchant: MerchantCanonical)

    @Query("SELECT * FROM merchant_canonicals WHERE id = :id")
    suspend fun getCanonicalById(id: Long): MerchantCanonical?

    @Query("SELECT * FROM merchant_canonicals WHERE searchKey = :searchKey LIMIT 1")
    suspend fun getCanonicalBySearchKey(searchKey: String): MerchantCanonical?

    @Query("SELECT * FROM merchant_canonicals WHERE normalizedName = :name LIMIT 1")
    suspend fun getCanonicalByName(name: String): MerchantCanonical?

    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC")
    suspend fun getAllCanonicals(): List<MerchantCanonical>

    @Query("SELECT * FROM merchant_canonicals ORDER BY totalOccurrences DESC LIMIT :limit")
    suspend fun getTopMerchants(limit: Int): List<MerchantCanonical>

    @Query("UPDATE merchant_canonicals SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCanonicalCategory(id: Long, categoryId: Long?)

    @Query("UPDATE merchant_canonicals SET totalOccurrences = totalOccurrences + 1, totalSpent = totalSpent + :amount, updatedAt = :timestamp WHERE id = :id")
    suspend fun incrementMerchantStats(id: Long, amount: Double, timestamp: Long = System.currentTimeMillis())

    // ==================== Merchant Aliases ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MerchantAlias): Long

    @Update
    suspend fun updateAlias(alias: MerchantAlias)

    @Query("SELECT * FROM merchant_aliases WHERE id = :id")
    suspend fun getAliasById(id: Long): MerchantAlias?

    @Query("SELECT * FROM merchant_aliases WHERE rawName = :rawName LIMIT 1")
    suspend fun getAliasByRawName(rawName: String): MerchantAlias?

    @Query("SELECT * FROM merchant_aliases WHERE normalizedKey = :normalizedKey LIMIT 1")
    suspend fun getAliasByNormalizedKey(normalizedKey: String): MerchantAlias?

    @Query("SELECT * FROM merchant_aliases WHERE canonicalId = :canonicalId")
    suspend fun getAliasesForCanonical(canonicalId: Long): List<MerchantAlias>

    @Query("""
        SELECT * FROM merchant_aliases 
        WHERE normalizedKey LIKE '%' || :query || '%'
        ORDER BY occurrenceCount DESC
        LIMIT :limit
    """)
    suspend fun searchAliases(query: String, limit: Int = 20): List<MerchantAlias>

    @Query("DELETE FROM merchant_aliases WHERE lastUsedAt < :olderThan")
    suspend fun deleteUnusedAliasesOlderThan(olderThan: Long): Int

    // ==================== Combined Operations ====================

    @Transaction
    suspend fun linkAliasToCanonical(rawName: String, canonicalId: Long, isUserDefined: Boolean = false) {
        val normalizedKey = rawName.lowercase().trim()
            .replace(Regex("[^a-z0-9α-ωά-ώ]"), "")

        val existing = getAliasByRawName(rawName)
        if (existing != null) {
            updateAlias(existing.copy(
                canonicalId = canonicalId,
                isUserDefined = isUserDefined || existing.isUserDefined,
                occurrenceCount = existing.occurrenceCount + 1,
                lastUsedAt = System.currentTimeMillis()
            ))
        } else {
            insertAlias(MerchantAlias(
                rawName = rawName,
                normalizedKey = normalizedKey,
                canonicalId = canonicalId,
                isUserDefined = isUserDefined
            ))
        }
    }

    @Query("SELECT COUNT(*) FROM merchant_canonicals")
    suspend fun getCanonicalCount(): Int
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingReviewDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(review: PendingReview): Long

    @Update
    suspend fun update(review: PendingReview)

    @Delete
    suspend fun delete(review: PendingReview)

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    fun getPendingFlow(limit: Int = 100): Flow<List<PendingReviewWithReceipt>>

    @Transaction
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getPending(limit: Int = 500): List<PendingReviewWithReceipt>

    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getById(id: Long): PendingReview?

    @Query("SELECT * FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun getByRawId(rawId: Long): PendingReview?

    @Query("DELETE FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun deleteByRawId(rawId: Long)

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun updateStatusIfPending(id: Long, status: String): Int

    @Query("SELECT * FROM pending_reviews ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PendingReview>>

    @Query("DELETE FROM pending_reviews WHERE status != 'PENDING'")
    suspend fun clearResolved()

    @Query("DELETE FROM pending_reviews")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\PlannedExpenseDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaoplannedexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedExpenseDao {
    @Query("SELECT * FROM planned_expenses ORDER BY date ASC")
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>>

    @Query("SELECT * FROM planned_expenses WHERE date BETWEEN :startMs AND :endMs")
    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlannedExpense(expense: PlannedExpense): Long

    @Delete
    suspend fun deletePlannedExpense(expense: PlannedExpense)

    @Query("DELETE FROM planned_expenses WHERE id = :id")
    suspend fun deletePlannedExpenseById(id: Long)
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface RawNotificationDao {

    @Insert
    suspend fun insert(notification: RawNotification): Long

    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getById(id: Long): RawNotification?

    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<RawNotification>>

    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<RawNotification>>

    @Query("SELECT * FROM raw_notifications WHERE packageName = :packageName ORDER BY capturedAt DESC")
    fun getByPackageFlow(packageName: String): Flow<List<RawNotification>>

    @Query("SELECT DISTINCT packageName FROM raw_notifications ORDER BY packageName")
    fun getAllPackagesFlow(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun getCountFlow(): Flow<Int>

    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(notification: RawNotification)

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications 
            WHERE packageName = :packageName 
            AND timestamp = :timestamp 
            AND (title = :title OR (:title IS NULL AND title IS NULL))
            AND (text = :text OR (:text IS NULL AND text IS NULL))
        )
    """)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean

    @Query("UPDATE raw_notifications SET isRelevant = :isRelevant WHERE id = :id")
    suspend fun markRelevance(id: Long, isRelevant: Boolean)
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\RecurringExpenseDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaorecurringexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringExpenseDao {
    @Query("SELECT * FROM manual_recurring_expenses ORDER BY nextDate ASC")
    fun getAllFlow(): Flow<List<ManualRecurringExpense>>

    @Query("SELECT * FROM manual_recurring_expenses")
    suspend fun getAll(): List<ManualRecurringExpense>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ManualRecurringExpense): Long

    @Update
    suspend fun update(expense: ManualRecurringExpense)

    @Delete
    suspend fun delete(expense: ManualRecurringExpense)

    @Query("SELECT * FROM manual_recurring_expenses WHERE merchant = :merchant LIMIT 1")
    suspend fun getByMerchant(merchant: String): ManualRecurringExpense?

    @Query("DELETE FROM manual_recurring_expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\SavingsGoalDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaosavingsgoaldaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SavingsGoal
import kotlinx.coroutines.flow.Flow

@Dao
interface SavingsGoalDao {
    @Query("SELECT * FROM savings_goals")
    fun getAllGoals(): Flow<List<SavingsGoal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(goal: SavingsGoal): Long

    @Delete
    suspend fun deleteGoal(goal: SavingsGoal)
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\ScannedReceiptDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaoscannedreceiptdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ScannedReceipt): Long

    @Update
    suspend fun update(receipt: ScannedReceipt)

    @Delete
    suspend fun delete(receipt: ScannedReceipt)

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScannedReceipt>>

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScannedReceipt>

    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?

    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int

    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()

    @Query("UPDATE scanned_receipts SET expenseId = :expenseId WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceStatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNotExists(stats: SourceStats)

    @Query("SELECT * FROM source_stats WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): SourceStats?

    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    fun getAllFlow(): Flow<List<SourceStats>>

    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    suspend fun getAll(): List<SourceStats>

    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1, 
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotal(packageName: String, now: Long = System.currentTimeMillis())

    @Query("""
        UPDATE source_stats 
        SET acceptedAsExpense = acceptedAsExpense + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAccepted(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET rejectedByUser = rejectedByUser + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementRejected(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET autoRejected = autoRejected + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAutoRejected(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET pendingReview = pendingReview + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementPending(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET duplicates = duplicates + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementDuplicate(packageName: String)

    @Query("""
        UPDATE source_stats 
        SET pendingReview = MAX(0, pendingReview - 1) 
        WHERE packageName = :packageName
    """)
    suspend fun decrementPending(packageName: String)

    @Query("UPDATE source_stats SET pendingReview = 0")
    suspend fun resetAllPendingCounts()

    @Query("DELETE FROM source_stats")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.UserCorrection
import kotlinx.coroutines.flow.Flow

@Dao
interface UserCorrectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(correction: UserCorrection): Long

    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<UserCorrection>>

    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    suspend fun getAll(): List<UserCorrection>

    @Query("SELECT COUNT(*) FROM user_corrections")
    suspend fun getCount(): Int

    // Get all corrections for a specific package (to learn its patterns)
    @Query("SELECT * FROM user_corrections WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): List<UserCorrection>

    // Get rejection rate for a package
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName AND wasRejected = 1
    """)
    suspend fun getRejectionCount(packageName: String): Int

    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName
    """)
    suspend fun getTotalCorrections(packageName: String): Int

    // Find merchant name corrections (user always renames X to Y)
    @Query("""
        SELECT correctedMerchant 
        FROM user_corrections 
        WHERE originalMerchant = :originalMerchant 
        AND correctedMerchant IS NOT NULL 
        AND correctedMerchant != originalMerchant
        GROUP BY correctedMerchant 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String?

    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant
    """)
    suspend fun getMerchantTotalCorrections(merchant: String): Int

    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant AND wasRejected = 1
    """)
    suspend fun getMerchantRejectionCount(merchant: String): Int

    @Query("""
        SELECT correctedCategoryId 
        FROM user_corrections 
        WHERE originalMerchant = :merchant 
        AND correctedCategoryId IS NOT NULL
        GROUP BY correctedCategoryId 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonCategoryForMerchant(merchant: String): Long?

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM user_corrections 
            WHERE packageName = :packageName 
            AND originalMerchant = :merchant 
            AND wasApproved = 1
        )
    """)
    suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean

    @Query("DELETE FROM user_corrections")
    suspend fun deleteAll()
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocked_packages")
data class BlockedPackage(
    @PrimaryKey
    val packageName: String,
    val blockedAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\Budget.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BudgetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}

@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"])
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,              // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    val startDate: Long,                // anchor date for period calculation
    val isActive: Boolean = true,
    val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    val rollover: Boolean = false,      // carry unspent to next period
    val createdAt: Long = System.currentTimeMillis(),
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\Category.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitycategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    val isDefault: Boolean = false // If true, cannot be deleted (easily)
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\Expense.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["transactionType", "date"]), // Replaces (date, transactionType) for better filtering
        Index(value = ["transactionType", "categoryId", "date"]), // Covers (categoryId, date) if filtered by type
        Index(value = ["categoryId", "date"]),      // For category breakdown and FK constraint
        Index(value = ["amount", "merchant", "date"]), // High specificity for duplicate check
        Index(value = ["merchant", "date"]), // Necessary for merchant-specific time searches
        Index(value = ["transactionType", "merchant", "date"]) // Restored by Migration 19->20
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val amount: Double,
    val currency: String = "EUR", // ISO 4217 Code

    val merchant: String, // Extracted merchant name

    val transactionType: TransactionType, // PURCHASE, WITHDRAWAL, etc.

    val date: Long, // Transaction date (best guess)

    val rawNotificationId: Long? = null, // Link to source

    val categoryId: Long? = null, // Link to category

    val createdAt: Long = System.currentTimeMillis(),

    // New fields
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null
)

enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}

enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\ManualRecurringExpense.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymanualrecurringexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yourname.expensetracker.domain.model.RecurrenceFrequency

@Entity(tableName = "manual_recurring_expenses")
data class ManualRecurringExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val merchant: String,
    val amount: Double,
    val currency: String = "EUR",
    val frequency: RecurrenceFrequency,
    val nextDate: Long,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantAlias.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantaliaskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_aliases",
    foreignKeys = [
        ForeignKey(
            entity = MerchantCanonical::class,
            parentColumns = ["id"],
            childColumns = ["canonicalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rawName"], unique = true),
        Index(value = ["normalizedKey"]),
        Index(value = ["canonicalId"])
    ]
)
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val rawName: String,        // e.g., "MCDONALD'S #1234"
    val normalizedKey: String,   // e.g., "mcdonalds1234"
    val canonicalId: Long,
    val occurrenceCount: Int = 1,
    val isUserDefined: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantCanonical.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantcanonicalkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_canonicals",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["normalizedName"], unique = true),
        Index(value = ["searchKey"]),
        Index(value = ["categoryId"])
    ]
)
data class MerchantCanonical(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val normalizedName: String, // e.g., "McDonald's"
    val searchKey: String,      // e.g., "mcdonalds" (stripped)
    val categoryId: Long? = null,
    val totalOccurrences: Int = 0,
    val totalSpent: Double = 0.0,
    val isVerified: Boolean = false,
    val logoUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "merchant_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"])]
)
data class MerchantCategory(
    @PrimaryKey
    val merchantPattern: String, // The normalized merchant name (e.g., "SKLAVENITIS")
    val categoryId: Long,
    val confidence: Float = 1.0f,
    val timesUsed: Int = 1
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ScannedReceipt::class,
            parentColumns = ["id"],
            childColumns = ["scannedReceiptId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["scannedReceiptId"]),
        Index(value = ["status"]),
        Index(value = ["status", "createdAt"])
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long?,
    val scannedReceiptId: Long? = null,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val suggestedDate: Long? = null,    // Added in v11 to preserve parsed date
    val confidence: Float,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING"      // PENDING, APPROVED, REJECTED, MODIFIED
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\PlannedExpense.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityplannedexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "planned_expenses",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["date"]),
        Index(value = ["categoryId"])
    ]
)
data class PlannedExpense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val amount: Double,
    val date: Long, // Planned date
    val categoryId: Long? = null,
    val isRecurring: Boolean = false,
    val priority: PlannedExpensePriority = PlannedExpensePriority.LIKELY,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PlannedExpensePriority {
    MUST,
    LIKELY,
    OPTIONAL
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]), // New: for sorting in Debug screen
        Index(value = ["isRelevant"]) // Optimized for filtering
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Source app
    val packageName: String,
    val appName: String?,

    // Notification content
    val title: String?,
    val text: String?,
    val bigText: String? = null,          // Expanded notification
    val subText: String? = null,

    // Raw extras as JSON string for debugging
    val extrasJson: String? = null,

    // Metadata
    val timestamp: Long,           // When notification was posted
    val capturedAt: Long,          // When we captured it

    // Processing status
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null    // JSON of parsed data or error message
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\SavingsGoal.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitysavingsgoalkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "savings_goals")
data class SavingsGoal(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val targetAmount: Double,
    val currentAmount: Double = 0.0,
    val targetDate: Long? = null,
    val protectionLevel: GoalProtectionLevel = GoalProtectionLevel.WARNING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class GoalProtectionLevel {
    STRICT,  // Fully reserved from discretionary
    WARNING, // Noted but not strictly subtracted
    TRACKING // Just for reference
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["createdAt"])
    ]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,
    val currency: String = "EUR",
    val confidence: Float,
    val expenseId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Long = 0,
    val acceptedAsExpense: Long = 0,
    val rejectedByUser: Long = 0,
    val autoRejected: Long = 0,
    val pendingReview: Long = 0,
    val duplicates: Long = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() {
            val valid = acceptedAsExpense + duplicates
            return if (totalNotifications > 0)
                valid.toFloat() / totalNotifications
            else 0f
        }

    val isLikelySpam: Boolean
        get() = totalNotifications > 10 && trustScore < 0.05f
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.*

@Entity(
    tableName = "user_corrections",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["originalCategoryId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["correctedCategoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("originalCategoryId"),
        Index("correctedCategoryId")
    ]
)
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val originalMerchant: String,
    val correctedMerchant: String?,
    val originalAmount: Double,
    val correctedAmount: Double?,
    val originalCategoryId: Long?,
    val correctedCategoryId: Long?,
    val wasRejected: Boolean = false,    // User said "this isn't a transaction"
    val wasApproved: Boolean = false,    // User confirmed it was correct
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\model\DashboardWidgetConfig.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasemodeldashboardwidgetconfigkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model

data class DashboardWidgetConfig(
    val id: String,
    val order: Int,
    val isVisible: Boolean = true
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*

/**
 * Optimized Room model for displaying transactions. 
 * Formatted strings are computed once when the object is instantiated from the DB,
 * preventing expensive re-calculation during LazyColumn scrolling.
 */
data class ExpenseWithCategory(
    @Embedded
    val expense: Expense,

    @Relation(
        parentColumn = "categoryId",
        entityColumn = "id"
    )
    val category: Category?
) {
    // Pre-computed formatting for UI efficiency
    val formattedDate: String by lazy {
        FORMATTER.get()?.format(Date(expense.date)) ?: ""
    }

    val formattedAmount: String by lazy {
        String.format(java.util.Locale.US, "%.2f %s", expense.amount, expense.currency)
    }

    val categoryColor: Long by lazy {
        try {
            category?.color?.let { android.graphics.Color.parseColor(it).toLong() } ?: android.graphics.Color.GRAY.toLong()
        } catch (e: Exception) {
            android.util.Log.e("ExpenseWithCategory", "Error parsing category color: ${category?.color}", e)
            android.graphics.Color.GRAY.toLong()
        }
    }

    companion object {
        private val FORMATTER = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat {
                return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\model\ExpenseWithCategory_Extensions.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasemodelexpensewithcategory_extensionskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model

import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extension properties for ExpenseWithCategory to provide formatted display values.
 */

// Date formatter with caching for performance
private val dateFormatCache = ThreadLocal<SimpleDateFormat>()

val ExpenseWithCategory.formattedDate: String
    get() {
        val formatter = dateFormatCache.get() ?: SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).also { dateFormatCache.set(it) }

        return try {
            formatter.format(Date(expense.date))
        } catch (e: Exception) {
            "Unknown"
        }
    }

val ExpenseWithCategory.formattedAmount: String
    get() {
        val prefix = when (expense.transactionType) {
            TransactionType.PURCHASE, TransactionType.WITHDRAWAL -> "-"
            TransactionType.DEPOSIT -> "+"
            else -> ""
        }
        return "$prefix${expense.currency}${String.format(Locale.getDefault(), "%.2f", expense.amount)}"
    }

```

---

## app\src\main\java\com\yourname\expensetracker\data\database\model\PendingReviewWithReceipt.kt <a name="appsrcmainjavacomyournameexpensetrackerdatadatabasemodelpendingreviewwithreceiptkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.model

import androidx.room.Embedded
import androidx.room.Relation
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt

data class PendingReviewWithReceipt(
    @Embedded val review: PendingReview,

    @Relation(
        parentColumn = "scannedReceiptId",
        entityColumn = "id"
    )
    val receipt: ScannedReceipt?
)

```

---

## app\src\main\java\com\yourname\expensetracker\data\provider\MerchantCategoryProvider.kt <a name="appsrcmainjavacomyournameexpensetrackerdataprovidermerchantcategoryproviderkt"></a>
```kotlin
package com.yourname.expensetracker.data.provider

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory

object MerchantCategoryProvider {

    // Default Categories (ID mapping presumed or we look them up)
    // Actually we will map by Name to be safe, then Repository will resolve IDs.

    val categoryBlueprints = listOf(
        Category(name = "Groceries", icon = "🛒", color = "#4CAF50", isDefault = true),
        Category(name = "Transport", icon = "🚗", color = "#2196F3", isDefault = true),
        Category(name = "Food", icon = "🍽️", color = "#FF9800", isDefault = true), // Restaurants/Cafe
        Category(name = "Entertainment", icon = "🎬", color = "#9C27B0", isDefault = true),
        Category(name = "Shopping", icon = "🛍️", color = "#E91E63", isDefault = true),
        Category(name = "Health", icon = "💊", color = "#00BCD4", isDefault = true),
        Category(name = "Utilities", icon = "🏠", color = "#607D8B", isDefault = true),
        Category(name = "Subscriptions", icon = "📱", color = "#673AB7", isDefault = true),
        Category(name = "Travel", icon = "✈️", color = "#009688", isDefault = true), // Changed color slightly
        Category(name = "Electronics", icon = "💻", color = "#795548", isDefault = true),
        Category(name = "Education", icon = "📚", color = "#3F51B5", isDefault = true),
        Category(name = "Fitness", icon = "💪", color = "#8BC34A", isDefault = true),
        Category(name = "Beauty", icon = "💄", color = "#FF4081", isDefault = true),
        Category(name = "Pets", icon = "🐾", color = "#A1887F", isDefault = true),
        Category(name = "Home", icon = "🛋️", color = "#FF5722", isDefault = true), // New from list
        Category(name = "Kids", icon = "🧸", color = "#FFEB3B", isDefault = true), // New from list
        Category(name = "Gifts", icon = "🎁", color = "#F44336", isDefault = true), // New from list
        Category(name = "Banking", icon = "🏦", color = "#37474F", isDefault = true), // Fees etc
        Category(name = "Legal & Gov", icon = "⚖️", color = "#9E9E9E", isDefault = true),
        Category(name = "Uncategorized", icon = "❓", color = "#BDBDBD", isDefault = true)
    )

    // Map of Merchant Name (or keyword) -> Category Name
    val merchantToCategoryMap = mapOf(
        // ═══════════════════════════════════════════════════════════════
        // 🛒 GROCERIES - Supermarkets, Bakeries, Butchers
        // ═══════════════════════════════════════════════════════════════

        // AB Vassilopoulos (all variations)
        "AB Βασιλόπουλος" to "Groceries", "AB Vasilopoulos" to "Groceries", 
        "AB BASILOPOULOS" to "Groceries", "AB SHOP" to "Groceries", 
        "A.B." to "Groceries", "ALFA BETA" to "Groceries",
        "AB FOOD MARKET" to "Groceries", "DELHAIZE" to "Groceries",
        "ΑΛΦΑ ΒΗΤΑ" to "Groceries", "TROFO" to "Groceries",

        // Sklavenitis
        "Σκλαβενίτης" to "Groceries", "Sklavenitis" to "Groceries", 
        "SKLAVENITIS" to "Groceries", "ELLINIKES YPERAGORES" to "Groceries",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Groceries", "I & S SKLAVENITIS" to "Groceries",

        // Lidl
        "Lidl" to "Groceries", "LIDL HELLAS" to "Groceries", 
        "LIDL ELLAS" to "Groceries", "LIDL STIFTUNG" to "Groceries",

        // My Market
        "My Market" to "Groceries", "MY MARKET" to "Groceries", 
        "MYMARKET" to "Groceries", "METRO AEBE" to "Groceries",
        "METRO MY MARKET" to "Groceries",

        // Masoutis
        "Μασούτης" to "Groceries", "Masoutis" to "Groceries", 
        "MASOUTIS" to "Groceries", "MASOYTHS" to "Groceries",
        "DIAMANTIS MASOUTIS" to "Groceries",

        // Other Greek Supermarkets
        "Γαλαξίας" to "Groceries", "Galaxias" to "Groceries", 
        "GALAXIAS" to "Groceries", "PENTE SA" to "Groceries",
        "Κρητικός" to "Groceries", "Kritikos" to "Groceries", 
        "KRITIKOS" to "Groceries", "ANEDIK KRITIKOS" to "Groceries",
        "Bazaar" to "Groceries", "BAZAAR" to "Groceries", 
        "BAZAAR SM" to "Groceries",
        "Market In" to "Groceries", "MARKET IN" to "Groceries", 
        "MARKETIN" to "Groceries", "VEROUKAS" to "Groceries",
        "The Mart" to "Groceries", "THE MART" to "Groceries", 
        "THEMART" to "Groceries", "MAKRO" to "Groceries",
        "METRO CASH" to "Groceries",

        // European Chains
        "Aldi" to "Groceries", "ALDI SUD" to "Groceries", 
        "ALDI NORD" to "Groceries",
        "Kaufland" to "Groceries", "KAUFLAND" to "Groceries",
        "Carrefour" to "Groceries", "CARREFOUR" to "Groceries",
        "CARREFOUR EXPRESS" to "Groceries", "CARREFOUR CITY" to "Groceries",
        "Penny Market" to "Groceries", "PENNY" to "Groceries",
        "Tesco" to "Groceries", "TESCO" to "Groceries",
        "Sainsbury" to "Groceries", "SAINSBURYS" to "Groceries",
        "Waitrose" to "Groceries", "WAITROSE" to "Groceries",
        "Marks Spencer Food" to "Groceries", "M&S FOOD" to "Groceries",
        "Migros" to "Groceries", "MIGROS" to "Groceries",
        "Coop" to "Groceries", "COOP" to "Groceries",
        "Spar" to "Groceries", "SPAR" to "Groceries",
        "Rewe" to "Groceries", "REWE" to "Groceries",
        "Edeka" to "Groceries", "EDEKA" to "Groceries",

        // Regional Greek Supermarkets
        "PLUS Super Discount" to "Groceries", "PLUS SUPERMARKET" to "Groceries",
        "Χαλκιαδάκης" to "Groceries", "Chalkiadakis" to "Groceries", 
        "HALKIADAKIS" to "Groceries", "XALKIADAKIS" to "Groceries",
        "OK! Anytime" to "Groceries", "OK MARKET" to "Groceries", 
        "OK ANYTIME MARKETS" to "Groceries",
        "Σάββας" to "Groceries", "Savvas" to "Groceries", 
        "SAVVAS CASH" to "Groceries",
        "3Α" to "Groceries", "3A" to "Groceries", "ΤΡΙΑ ΑΛΦΑ" to "Groceries",
        "Discount Markt" to "Groceries", "DISCOUNT MARKT" to "Groceries",
        "Arvanitidis" to "Groceries", "ΑΡΒΑΝΙΤΙΔΗΣ" to "Groceries",
        "Atlantic" to "Groceries", "ATLANTIC" to "Groceries",
        "Synka" to "Groceries", "SYNKA" to "Groceries", "ΣΥΝΚΑ" to "Groceries",
        "Xynos" to "Groceries", "ΞΥΝΟΣ" to "Groceries",
        "Ena Cash" to "Groceries", "ENA CASH CARRY" to "Groceries",
        "Smile Markets" to "Groceries", "SMILE MARKETS" to "Groceries",
        "Karamolegos" to "Groceries", "ΚΑΡΑΜΟΛΕΓΚΟΣ" to "Groceries",

        // Bio/Organic Stores
        "Bio Agora" to "Groceries", "BIO AGORA" to "Groceries",
        "Ελαία" to "Groceries", "Elaia" to "Groceries",
        "Avocado" to "Groceries", "AVOCADO STORES" to "Groceries",
        "Green Family" to "Groceries", "GREEN FAMILY" to "Groceries",
        "Organic" to "Groceries", "ORGANIC SHOP" to "Groceries",
        "Bio" to "Groceries", "BIOLOGIKA" to "Groceries",
        "Herbs" to "Groceries", "HERBS STORE" to "Groceries",

        // Convenience & Local
        "Mini Market" to "Groceries", "Minimarket" to "Groceries", 
        "Μινι Μαρκετ" to "Groceries",
        "Kiosk" to "Groceries", "Periptero" to "Groceries", 
        "Περίπτερο" to "Groceries", "PERIPTERO" to "Groceries",
        "Psilika" to "Groceries", "Ψιλικα" to "Groceries",
        "Pantopoleio" to "Groceries", "Παντοπωλείο" to "Groceries",
        "Grocery" to "Groceries", "GROCERY STORE" to "Groceries",
        "Bakaliko" to "Groceries", "Μπακάλικο" to "Groceries",
        "Express Market" to "Groceries", "EXPRESS" to "Groceries",

        // Bakeries
        "Bakery" to "Groceries", "Baker" to "Groceries", 
        "Φούρνος" to "Groceries", "Fournos" to "Groceries", 
        "Artopoiio" to "Groceries", "Αρτοποιείο" to "Groceries",
        "ARTOS" to "Groceries", "Bread" to "Groceries",
        "Veneti" to "Groceries", "ΒΕΝΕΤΗ" to "Groceries", "VENETIS" to "Groceries",
        "Terkenlis" to "Groceries", "ΤΕΡΚΕΝΛΗΣ" to "Groceries",
        "Asimakopoulou" to "Groceries", "ΑΣΗΜΑΚΟΠΟΥΛΟΥ" to "Groceries",
        "Konstantinidis" to "Groceries", "ΚΩΝΣΤΑΝΤΙΝΙΔΗΣ" to "Groceries",
        "Chatzis" to "Groceries", "ΧΑΤΖΗΣ" to "Groceries",
        "Blé" to "Groceries", "BLE" to "Groceries",
        "Pain Quotidien" to "Groceries", "PAIN QUOTIDIEN" to "Groceries",

        // Butcher/Meat
        "Butcher" to "Groceries", "Kreopoleio" to "Groceries", 
        "Κρεοπωλείο" to "Groceries", "KREAS" to "Groceries",
        "Meat" to "Groceries", "MEAT SHOP" to "Groceries",
        "Chiros" to "Groceries", "Pork Shop" to "Groceries",
        "Salami" to "Groceries", "Allantika" to "Groceries",
        "ΑΛΛΑΝΤΙΚΑ" to "Groceries",

        // Fish
        "Fish Shop" to "Groceries", "Ixthiopolio" to "Groceries", 
        "Ιχθυοπωλείο" to "Groceries", "ΨΑΡΑΓΟΡΑ" to "Groceries",
        "Fish Market" to "Groceries", "Psaradiko" to "Groceries",
        "Seafood" to "Groceries",

        // Produce
        "Greengrocer" to "Groceries", "Manaviko" to "Groceries", 
        "Μανάβικο" to "Groceries", "ΛΑΪΚΗ" to "Groceries",
        "Laiki Agora" to "Groceries", "Farmers Market" to "Groceries",
        "ΑΓΟΡΑ" to "Groceries", "Varvakios" to "Groceries",
        "ΒΑΡΒΑΚΕΙΟΣ" to "Groceries",

        // Specialty
        "Cheese Shop" to "Groceries", "Tyrokomeio" to "Groceries",
        "Delicatessen" to "Groceries", "Deli" to "Groceries",
        "Kafekopeio" to "Groceries", "ΚΑΦΕΚΟΠΤΕΙΟ" to "Groceries",
        "Wine Shop" to "Groceries", "Kava" to "Groceries", "ΚΑΒΑ" to "Groceries",
        "Cellar" to "Groceries",

        // ═══════════════════════════════════════════════════════════════
        // 🚗 TRANSPORT - FUEL & MOBILITY
        // ═══════════════════════════════════════════════════════════════

        // Fuel Stations
        "Shell" to "Transport", "SHELL HELLAS" to "Transport", 
        "SEHL" to "Transport", "CORAL AE" to "Transport",
        "BP" to "Transport", "BP HELLAS" to "Transport", 
        "BRITISH PETROLEUM" to "Transport",
        "EKO" to "Transport", "EKO ABEE" to "Transport", 
        "EKO KALYPSO" to "Transport", "HELLENIC PETROLEUM" to "Transport",
        "ELPE" to "Transport", "ΕΛΠΕ" to "Utilities", // Note: ELPE can be heating oil/utility too, but keeping as fuel here or user preference
        "Aegean" to "Transport", "Aegean Oil" to "Transport", 
        "AEGEAN OIL" to "Transport",
        "Avin" to "Transport", "AVIN OIL" to "Transport", 
        "MOTOR OIL" to "Transport",
        "Ελίν" to "Transport", "Elin" to "Transport", 
        "ELIN OIL" to "Transport", "ELINOIL" to "Transport",
        "Revoil" to "Transport", "REVOIL" to "Transport", 
        "Jet Oil" to "Transport", "JETOIL" to "Transport",
        "Cyclon" to "Transport", "CYCLON" to "Transport", 
        "Coral Gas" to "Transport", "CORAL GAS" to "Transport",
        "Eteka" to "Transport", "ETEKA" to "Transport",
        "Mamidoil" to "Transport", "MAMIDOIL" to "Transport",
        "Silk Oil" to "Transport", "SILK OIL" to "Transport",
        "Naoumidis" to "Transport", "ΝΑΟΥΜΙΔΗΣ" to "Transport",

        // International Fuel
        "Total" to "Transport", "TOTAL ENERGIES" to "Transport",
        "Esso" to "Transport", "ESSO" to "Transport",
        "Texaco" to "Transport", "TEXACO" to "Transport",
        "Q8" to "Transport", "KUWAIT PETROLEUM" to "Transport",
        "Cepsa" to "Transport", "CEPSA" to "Transport",
        "Repsol" to "Transport", "REPSOL" to "Transport",
        "OMV" to "Transport", "OMV" to "Transport",
        "MOL" to "Transport", "MOL" to "Transport",

        // Generic Fuel
        "Gas Station" to "Transport", "Fuel Station" to "Transport", 
        "Πρατήριο" to "Transport", "Benzinadiko" to "Transport",
        "Βενζινάδικο" to "Transport", "PRATIRIO" to "Transport",
        "Petrol" to "Transport", "PETROL" to "Transport",
        "Diesel" to "Transport", "DIESEL" to "Transport",
        "LPG" to "Transport", "AUTOGAS" to "Transport",
        "Charging Station" to "Transport", "EV CHARGE" to "Transport",

        // Ride Hailing
        "Uber" to "Transport", "UBER TRIP" to "Transport", 
        "UBER BV" to "Transport", "UBER PAYMENTS" to "Transport",
        "Beat" to "Transport", "BEAT APP" to "Transport", 
        "BEAT RIDE" to "Transport",
        "FREE NOW" to "Transport", "NOOW" to "Transport", 
        "FREENOW" to "Transport", "MYTAXI" to "Transport",
        "Bolt" to "Transport", "BOLT EU" to "Transport",
        "BOLT OPERATIONS" to "Transport",
        "Lyft" to "Transport", "LYFT" to "Transport",
        "Didi" to "Transport", "DIDI" to "Transport",

        // Taxis
        "Taxi" to "Transport", "Ταξί" to "Transport", 
        "Cab" to "Transport", "ΤΑΞΙ" to "Transport",
        "Taxiplon" to "Transport", "TAXIPLON" to "Transport",
        "Radio Taxi" to "Transport", "RADIO TAXI" to "Transport",
        "TAXI ATHINON" to "Transport", "ΡΑΔΙΟΤΑΞΙ" to "Transport",

        // Public Transport Athens
        "OASA" to "Transport", "ΟΑΣΑ" to "Transport", 
        "ATH.ENA TICKET" to "Transport", "ATHENA CARD" to "Transport",
        "STASY" to "Transport", "ΣΤΑΣΥ" to "Transport", 
        "URBAN RAIL" to "Transport",
        "Metro Athens" to "Transport", "ΜΕΤΡΟ" to "Transport",
        "ATTIKO METRO" to "Transport",
        "Tram" to "Transport", "Τραμ" to "Transport", "ΤΡΑΜ" to "Transport",
        "ISAP" to "Transport", "ΗΣΑΠ" to "Transport", 
        "Ηλεκτρικός" to "Transport", "HLEKTRIKOS" to "Transport",
        "Proastiakos" to "Transport", "ΠΡΟΑΣΤΙΑΚΟΣ" to "Transport",

        // Public Transport Thessaloniki
        "OASTH" to "Transport", "ΟΑΣΘ" to "Transport",
        "THESSALONIKI METRO" to "Transport",

        // Buses
        "KTEL" to "Transport", "ΚΤΕΛ" to "Transport", 
        "KTEL ATTIKIS" to "Transport", "KTEL MACEDONIA" to "Transport",
        "KTEL PELOPONNISOU" to "Transport", "KTEL KRITIS" to "Transport",
        "KTEL THESSALONIKH" to "Transport", "KTEL LARISAS" to "Transport",
        "KTEL PATRON" to "Transport", "KTEL VOLOU" to "Transport",
        "KTEL EVIA" to "Transport", "KTEL IRAKLEIOU" to "Transport",
        "KTEL CHANION" to "Transport", "KTEL RODOU" to "Transport",
        "Flixbus" to "Transport", "FLIXBUS" to "Transport",

        // Trains
        "Hellenic Train" to "Transport", "TRAINOSE" to "Transport", 
        "ΤΡΕΝΟΣΕ" to "Transport", "OSE" to "Transport", "ΟΣΕ" to "Transport",
        "HELLENICTRAIN" to "Transport", "ΕΛΛΗΝΙΚΟΣ" to "Transport",
        "Eurostar" to "Transport", "EUROSTAR" to "Transport",
        "Thalys" to "Transport", "TGV" to "Transport",
        "Deutsche Bahn" to "Transport", "DB" to "Transport",
        "OBB" to "Transport", "SNCF" to "Transport",
        "Trenitalia" to "Transport", "ITALO" to "Transport",

        // Parking
        "Parking" to "Transport", "Parkin" to "Transport", 
        "Parkingmycity" to "Transport", "Cityzen" to "Transport", 
        "Polis Park" to "Transport",
        "Athens Parking" to "Transport", "APCOA" to "Transport",
        "Q-Park" to "Transport", "INTERPARKING" to "Transport",
        "SABA" to "Transport", "ΣΤΑΘΜΕΥΣΗ" to "Transport",
        "Valet" to "Transport", "VALET PARKING" to "Transport",

        // Tolls
        "E-pass" to "Transport", "EPASS" to "Transport", 
        "Attiki Odos" to "Transport", "ATTIKI ODOS" to "Transport",
        "ATTIKES DIADROMES" to "Transport",
        "Nea Odos" to "Transport", "NEA ODOS" to "Transport",
        "Olympia Odos" to "Transport", "OLYMPIA ODOS" to "Transport",
        "Egnatia Odos" to "Transport", "EGNATIA ODOS" to "Transport",
        "Moreas" to "Transport", "MOREAS" to "Transport", 
        "Kentriki Odos" to "Transport", "KENTRIKI ODOS" to "Transport",
        "Gefyra" to "Transport", "GEFYRA" to "Transport", 
        "Rio Antirio" to "Transport", "RIO ANTIRIO" to "Transport",
        "DIODIA" to "Transport", "Διόδια" to "Transport",
        "Aktor" to "Transport", "AKTOR CONCESSIONS" to "Transport",
        "Autohellas" to "Transport", "AUTOKINITODROMO" to "Transport",

        // Micromobility
        "Lime" to "Transport", "LIME SCOOTER" to "Transport",
        "Tier" to "Transport", "TIER MOBILITY" to "Transport",
        "Bird" to "Transport", "BIRD SCOOTER" to "Transport",
        "Voi" to "Transport", "VOI" to "Transport",
        "Dott" to "Transport", "DOTT" to "Transport",
        "Spin" to "Transport", "SPIN" to "Transport",
        "Bike" to "Transport", "BIKE RENTAL" to "Transport",
        "E-scooter" to "Transport", "ESCOOTER" to "Transport",

        // ═══════════════════════════════════════════════════════════════
        // ✈️ TRAVEL - Airlines, Hotels, Ferries
        // ═══════════════════════════════════════════════════════════════

        // Greek Airlines
        "Aegean Airlines" to "Travel", "AEGEAN AIR" to "Travel", 
        "AEGEAN AIRLINES" to "Travel", "A3" to "Travel",
        "Olympic Air" to "Travel", "OLYMPIC AIR" to "Travel", 
        "OLYMPIC AIRLINES" to "Travel",
        "Sky Express" to "Travel", "SKY EXPRESS" to "Travel",
        "SKYEXPRESS" to "Travel",

        // European Low Cost
        "Ryanair" to "Travel", "RYANAIR" to "Travel", 
        "RYANAIR DAC" to "Travel", "FR" to "Travel",
        "EasyJet" to "Travel", "EASYJET" to "Travel", 
        "U2" to "Travel",
        "Wizz Air" to "Travel", "WIZZAIR" to "Travel", 
        "W6" to "Travel",
        "Volotea" to "Travel", "VOLOTEA" to "Travel",
        "Vueling" to "Travel", "VUELING" to "Travel",
        "Transavia" to "Travel", "TRANSAVIA" to "Travel",
        "Norwegian" to "Travel", "NORWEGIAN AIR" to "Travel",
        "Eurowings" to "Travel", "EUROWINGS" to "Travel",
        "Lauda" to "Travel", "LAUDA EUROPE" to "Travel",
        "Buzz" to "Travel", "BUZZ POLAND" to "Travel",
        "Malta Air" to "Travel", "MALTA AIR" to "Travel",

        // Major Airlines
        "Lufthansa" to "Travel", "LUFTHANSA" to "Travel", "LH" to "Travel",
        "Swiss Air" to "Travel", "SWISS" to "Travel", "LX" to "Travel",
        "Austrian" to "Travel", "AUSTRIAN AIRLINES" to "Travel",
        "British Airways" to "Travel", "BRITISH AIRWAYS" to "Travel", "BA" to "Travel",
        "Air France" to "Travel", "AIRFRANCE" to "Travel", "AF" to "Travel",
        "KLM" to "Travel", "KLM ROYAL" to "Travel",
        "Iberia" to "Travel", "IBERIA" to "Travel",
        "TAP" to "Travel", "TAP PORTUGAL" to "Travel",
        "Alitalia" to "Travel", "ITA AIRWAYS" to "Travel",
        "SAS" to "Travel", "SCANDINAVIAN" to "Travel",
        "Finnair" to "Travel", "FINNAIR" to "Travel",
        "LOT" to "Travel", "LOT POLISH" to "Travel",
        "Czech Airlines" to "Travel", "CSA" to "Travel",
        "Croatia Airlines" to "Travel", "CROATIA AIR" to "Travel",
        "Turkish Airlines" to "Travel", "TURKISH" to "Travel", "TK" to "Travel",
        "Emirates" to "Travel", "EMIRATES" to "Travel", "EK" to "Travel",
        "Qatar Airways" to "Travel", "QATAR" to "Travel", "QR" to "Travel",
        "Etihad" to "Travel", "ETIHAD" to "Travel",
        "Singapore Airlines" to "Travel", "SINGAPORE AIR" to "Travel",
        "Cathay Pacific" to "Travel", "CATHAY" to "Travel",
        "United" to "Travel", "UNITED AIRLINES" to "Travel",
        "Delta" to "Travel", "DELTA AIRLINES" to "Travel",
        "American Airlines" to "Travel", "AMERICAN AIR" to "Travel",

        // Ferries - Greece
        "Blue Star" to "Travel", "BLUE STAR FERRIES" to "Travel",
        "BLUESTAR" to "Travel", "ATTICA GROUP" to "Travel",
        "ANEK" to "Travel", "ANEK LINES" to "Travel",
        "Minoan" to "Travel", "MINOAN LINES" to "Travel",
        "Hellenic Seaways" to "Travel", "HSW" to "Travel",
        "HELLENIC SEAWAYS" to "Travel",
        "Seajets" to "Travel", "SEAJETS" to "Travel", "SEA JETS" to "Travel",
        "Golden Star" to "Travel", "GOLDEN STAR FERRIES" to "Travel",
        "Fast Ferries" to "Travel", "FAST FERRIES" to "Travel",
        "Superfast" to "Travel", "SUPERFAST FERRIES" to "Travel",
        "Aegean Speed Lines" to "Travel", "AEGEAN SPEED" to "Travel",
        "Zante Ferries" to "Travel", "ZANTE FERRIES" to "Travel",
        "Levante Ferries" to "Travel", "LEVANTE" to "Travel",
        "Saronic Ferries" to "Travel", "SARONIC" to "Travel",
        "Anes Ferries" to "Travel", "ANES" to "Travel",
        "NEL Lines" to "Travel", "NEL" to "Travel",
        "Sky Island Ferries" to "Travel", "SKYISLAND" to "Travel",
        "Dodekanisos Seaways" to "Travel", "DODEKANISOS" to "Travel",
        "Small Cyclades" to "Travel", "EXPRESS SKOPELITIS" to "Travel",
        "Triton" to "Travel", "TRITON FERRIES" to "Travel",
        "Ferry" to "Travel", "FERRY TICKET" to "Travel",
        "ΠΛΟΙΟ" to "Travel", "ΑΚΤΟΠΛΟΙΚΑ" to "Travel",

        // International Ferries
        "Grimaldi" to "Travel", "GRIMALDI LINES" to "Travel",
        "Grandi Navi" to "Travel", "GNV" to "Travel",
        "Moby" to "Travel", "MOBY LINES" to "Travel",
        "Tirrenia" to "Travel", "TIRRENIA" to "Travel",
        "Jadrolinija" to "Travel", "JADROLINIJA" to "Travel",
        "Corsica Ferries" to "Travel", "CORSICA" to "Travel",
        "Brittany Ferries" to "Travel", "BRITTANY" to "Travel",
        "P&O Ferries" to "Travel", "P&O" to "Travel",
        "DFDS" to "Travel", "DFDS SEAWAYS" to "Travel",
        "Stena Line" to "Travel", "STENA" to "Travel",
        "Viking Line" to "Travel", "VIKING LINE" to "Travel",
        "Tallink" to "Travel", "TALLINK SILJA" to "Travel",

        // Car Rental
        "Hertz" to "Travel", "HERTZ" to "Travel", "HERTZ HELLAS" to "Travel",
        "Avis" to "Travel", "AVIS" to "Travel", "AVIS RENT" to "Travel",
        "Europcar" to "Travel", "EUROPCAR" to "Travel",
        "Enterprise" to "Travel", "ENTERPRISE" to "Travel",
        "Budget" to "Travel", "BUDGET" to "Travel",
        "Sixt" to "Travel", "SIXT" to "Travel", "SIXT RENT" to "Travel",
        "National" to "Travel", "NATIONAL CAR" to "Travel",
        "Alamo" to "Travel", "ALAMO" to "Travel",
        "Thrifty" to "Travel", "THRIFTY" to "Travel",
        "Dollar" to "Travel", "DOLLAR RENT" to "Travel",
        "Green Motion" to "Travel", "GREEN MOTION" to "Travel",
        "Goldcar" to "Travel", "GOLDCAR" to "Travel",
        "Firefly" to "Travel", "FIREFLY CAR" to "Travel",
        "Maggiore" to "Travel", "MAGGIORE RENT" to "Travel",
        "Autohellas" to "Travel", "AUTOHELLAS" to "Travel",
        "Avance" to "Travel", "AVANCE RENT" to "Travel",
        "Car Rental" to "Travel", "RENT A CAR" to "Travel",
        "ΕΝΟΙΚΙΑΣΗ" to "Travel",

        // Hotels & Accommodation
        "Booking.com" to "Travel", "BOOKING" to "Travel", 
        "BOOKING.COM" to "Travel", "BOOKINGCOM" to "Travel",
        "Airbnb" to "Travel", "AIRBNB" to "Travel", 
        "AIR BNB" to "Travel",
        "Hotels.com" to "Travel", "HOTELS.COM" to "Travel",
        "Expedia" to "Travel", "EXPEDIA" to "Travel",
        "Trivago" to "Travel", "TRIVAGO" to "Travel",
        "Agoda" to "Travel", "AGODA" to "Travel",
        "Trip.com" to "Travel", "TRIP.COM" to "Travel", 
        "CTRIP" to "Travel",
        "Hostelworld" to "Travel", "HOSTELWORLD" to "Travel",
        "Vrbo" to "Travel", "VRBO" to "Travel", 
        "HOMEAWAY" to "Travel",
        "TripAdvisor" to "Travel", "TRIPADVISOR" to "Travel",
        "Kayak" to "Travel", "KAYAK" to "Travel",
        "Skyscanner" to "Travel", "SKYSCANNER" to "Travel",
        "Google Flights" to "Travel", "GOOGLE FLIGHTS" to "Travel",
        "Momondo" to "Travel", "MOMONDO" to "Travel",
        "Kiwi" to "Travel", "KIWI.COM" to "Travel",
        "Lastminute" to "Travel", "LASTMINUTE" to "Travel",
        "Opodo" to "Travel", "OPODO" to "Travel",
        "eDreams" to "Travel", "EDREAMS" to "Travel",
        "Gotogate" to "Travel", "GOTOGATE" to "Travel",
        "Hotel" to "Travel", "HOTEL" to "Travel", 
        "ΞΕΝΟΔΟΧΕΙΟ" to "Travel", "Xenodocheio" to "Travel",
        "Hostel" to "Travel", "HOSTEL" to "Travel",
        "Resort" to "Travel", "RESORT" to "Travel",
        "Pension" to "Travel", "PENSION" to "Travel",
        "Motel" to "Travel", "MOTEL" to "Travel",

        // Greek Hotel Chains
        "Grecotel" to "Travel", "GRECOTEL" to "Travel",
        "Mitsis" to "Travel", "MITSIS HOTELS" to "Travel",
        "Aldemar" to "Travel", "ALDEMAR" to "Travel",
        "Porto Carras" to "Travel", "PORTO CARRAS" to "Travel",
        "Sani Resort" to "Travel", "SANI" to "Travel",
        "Ikos" to "Travel", "IKOS RESORTS" to "Travel",
        "Costa Navarino" to "Travel", "COSTA NAVARINO" to "Travel",
        "Divani" to "Travel", "DIVANI HOTELS" to "Travel",
        "Electra" to "Travel", "ELECTRA HOTELS" to "Travel",
        "Makedonia Palace" to "Travel", "MAKEDONIA PALACE" to "Travel",
        "Grande Bretagne" to "Travel", "GRANDE BRETAGNE" to "Travel",
        "King George" to "Travel", "KING GEORGE" to "Travel",
        "St George Lycabettus" to "Travel", "ST GEORGE" to "Travel",
        "Hilton Athens" to "Travel", "HILTON" to "Travel",
        "Marriott" to "Travel", "MARRIOTT" to "Travel",
        "Sofitel" to "Travel", "SOFITEL" to "Travel",
        "Intercontinental" to "Travel", "INTERCONTINENTAL" to "Travel",
        "Four Seasons" to "Travel", "FOUR SEASONS" to "Travel",
        "Radisson" to "Travel", "RADISSON BLU" to "Travel",
        "Wyndham" to "Travel", "WYNDHAM" to "Travel",
        "Novotel" to "Travel", "NOVOTEL" to "Travel",
        "Ibis" to "Travel", "IBIS" to "Travel",
        "Accor" to "Travel", "ACCOR" to "Travel",
        "Best Western" to "Travel", "BEST WESTERN" to "Travel",
        "Holiday Inn" to "Travel", "HOLIDAY INN" to "Travel",
        "Crowne Plaza" to "Travel", "CROWNE PLAZA" to "Travel",

        // Tour Operators
        "TUI" to "Travel", "TUI HELLAS" to "Travel",
        "Thomas Cook" to "Travel", "THOMAS COOK" to "Travel",
        "Mouzenidis" to "Travel", "MOUZENIDIS TRAVEL" to "Travel",
        "Zorpidis" to "Travel", "ZORPIDIS TRAVEL" to "Travel",
        "Amphitrion" to "Travel", "AMPHITRION" to "Travel",
        "Travelplanet24" to "Travel", "TRAVELPLANET" to "Travel",
        "Pamediakopes" to "Travel", "PAME DIAKOPES" to "Travel",
        "Discover Greece" to "Travel", "DISCOVER" to "Travel",
        "Aegean Holidays" to "Travel", "AEGEAN HOLIDAYS" to "Travel",
        "Travel Agency" to "Travel", "TRAVEL AGENCY" to "Travel",
        "ΤΑΞΙΔΙΩΤΙΚΟ" to "Travel", "Tour" to "Travel",

        // Activities & Experiences
        "GetYourGuide" to "Travel", "GETYOURGUIDE" to "Travel",
        "Viator" to "Travel", "VIATOR" to "Travel",
        "Klook" to "Travel", "KLOOK" to "Travel",
        "Musement" to "Travel", "MUSEMENT" to "Travel",
        "Tiqets" to "Travel", "TIQETS" to "Travel",
        "Civitatis" to "Travel", "CIVITATIS" to "Travel",
        "Headout" to "Travel", "HEADOUT" to "Travel",

        // ═══════════════════════════════════════════════════════════════
        // 🍽️ FOOD & RESTAURANTS
        // ═══════════════════════════════════════════════════════════════

        // Coffee Chains - Greek
        "Gregorys" to "Food", "GREGORYS" to "Food", 
        "GRIGORIS" to "Food", "Γρηγόρης" to "Food", 
        "MΙΚΡΟΓΕΥΜΑΤΑ" to "Food", "ΓΡΗΓΟΡΗΣ" to "Food",
        "Everest" to "Food", "EVEREST" to "Food",
        "Mikel" to "Food", "MIKEL" to "Food", 
        "MIKEL COFFEE" to "Food",
        "Coffee Island" to "Food", "COFFEE ISLAND" to "Food", 
        "KAFEKOPTEIO" to "Food",
        "Coffee Lab" to "Food", "COFFEE LAB" to "Food",
        "Flocafe" to "Food", "FLOCAFE" to "Food",
        "Coffee Berry" to "Food", "COFFEE BERRY" to "Food",
        "Bruno" to "Food", "BRUNO COFFEE" to "Food", 
        "Cultivos" to "Food", "CULTIVOS" to "Food",
        "Taf" to "Food", "TAF COFFEE" to "Food",
        "Holy Spirit" to "Food", "HOLY SPIRIT" to "Food",
        "Brew Lab" to "Food", "BREW LAB" to "Food",
        "Mokka" to "Food", "MOKKA COFFEE" to "Food",
        "The Underdog" to "Food", "UNDERDOG" to "Food",
        "Little Tree" to "Food", "LITTLE TREE" to "Food",
        "Seven Grams" to "Food", "SEVEN GRAMS" to "Food",
        "Mind the Cup" to "Food", "MIND THE CUP" to "Food",

        // Coffee Chains - International
        "Starbucks" to "Food", "STARBUCKS" to "Food", 
        "STARBUCKS COFFEE" to "Food",
        "Costa Coffee" to "Food", "COSTA COFFEE" to "Food",
        "McCafe" to "Food", "MCCAFE" to "Food",
        "Caffè Nero" to "Food", "CAFFE NERO" to "Food",
        "Pret" to "Food", "PRET A MANGER" to "Food",
        "Dunkin" to "Food", "DUNKIN DONUTS" to "Food",
        "Tim Hortons" to "Food", "TIM HORTONS" to "Food",
        "Gloria Jeans" to "Food", "GLORIA JEANS" to "Food",
        "Lavazza" to "Food", "LAVAZZA" to "Food",
        "Illy" to "Food", "ILLY CAFFE" to "Food",
        "Segafredo" to "Food", "SEGAFREDO" to "Food",

        // Fast Food - Global
        "McDonalds" to "Food", "MCDONALDS" to "Food", 
        "MCD" to "Food", "MC DONALDS" to "Food",
        "Burger King" to "Food", "BURGER KING" to "Food", "BK" to "Food",
        "KFC" to "Food", "KENTUCKY FRIED CHICKEN" to "Food",
        "Subway" to "Food", "SUBWAY" to "Food",
        "Pizza Hut" to "Food", "PIZZA HUT" to "Food",
        "Dominos" to "Food", "DOMINOS" to "Food", 
        "DOMINO'S" to "Food", "DOMINOS PIZZA" to "Food",
        "Papa Johns" to "Food", "PAPA JOHNS" to "Food",
        "Wendys" to "Food", "WENDYS" to "Food",
        "Taco Bell" to "Food", "TACO BELL" to "Food",
        "Chick-fil-A" to "Food", "CHICK FIL A" to "Food",
        "Five Guys" to "Food", "FIVE GUYS" to "Food",
        "Shake Shack" to "Food", "SHAKE SHACK" to "Food",
        "Popeyes" to "Food", "POPEYES" to "Food",
        "Chipotle" to "Food", "CHIPOTLE" to "Food",

        // Fast Food - Greek
        "Goodys" to "Food", "Goody's" to "Food", 
        "GOODYS" to "Food", "GOODY'S BURGER HOUSE" to "Food",
        "Pizza Fan" to "Food", "PIZZA FAN" to "Food",
        "Roma Pizza" to "Food", "ROMA PIZZA" to "Food",
        "L'Artigiano" to "Food", "LARTIGIANO" to "Food",
        "Palmie Bistro" to "Food", "PALMIE" to "Food",
        "Bufala Gelato" to "Food", "BUFALA" to "Food",

        // Casual Dining
        "TGI Fridays" to "Food", "TGI FRIDAYS" to "Food", 
        "FRIDAYS" to "Food",
        "Hard Rock" to "Food", "HARD ROCK CAFE" to "Food",
        "Wagamama" to "Food", "WAGAMAMA" to "Food", 
        "Noodle Bar" to "Food", "NOODLE BAR" to "Food",
        "Applebees" to "Food", "APPLEBEES" to "Food",
        "Chillis" to "Food", "CHILIS" to "Food",
        "Olive Garden" to "Food", "OLIVE GARDEN" to "Food",
        "PF Changs" to "Food", "PF CHANGS" to "Food",
        "Vapiano" to "Food", "VAPIANO" to "Food",
        "Bills" to "Food", "BILLS" to "Food",
        "The Breakfast Club" to "Food", "BREAKFAST CLUB" to "Food",

        // Greek Food Categories
        "Souvlaki" to "Food", "Σουβλάκι" to "Food", 
        "ΣΟΥΒΛΑΚΙ" to "Food", "SOUVLATZIDIKO" to "Food",
        "Psistaria" to "Food", "Ψησταριά" to "Food", 
        "ΨΗΣΤΑΡΙΑ" to "Food",
        "Grill" to "Food", "GRILL" to "Food", "ΣΧΑΡΑΣ" to "Food",
        "Gyros" to "Food", "ΓΥΡΟΣ" to "Food",
        "Kebab" to "Food", "KEBAB" to "Food",
        "Taverna" to "Food", "Ταβέρνα" to "Food", 
        "ΤΑΒΕΡΝΑ" to "Food",
        "Mezedopolio" to "Food", "Μεζεδοπωλείο" to "Food",
        "Ouzeri" to "Food", "Ουζερί" to "Food",
        "Tsipouradiko" to "Food", "Τσιπουράδικο" to "Food",
        "Psarotaverna" to "Food", "Ψαροταβέρνα" to "Food",
        "Estiatorio" to "Food", "Εστιατόριο" to "Food",
        "Restaurant" to "Food", "RESTAURANT" to "Food",

        // Cafes & Bars
        "Cafe" to "Food", "Καφέ" to "Food", "ΚΑΦΕ" to "Food",
        "Kafeneio" to "Food", "Καφενείο" to "Food",
        "Bar" to "Food", "BAR" to "Food", "ΜΠΑΡ" to "Food",
        "Club" to "Food", "CLUB" to "Food", "ΚΛΑΜΠ" to "Food",
        "Pub" to "Food", "PUB" to "Food",
        "Lounge" to "Food", "LOUNGE" to "Food",
        "Bistro" to "Food", "BISTRO" to "Food",
        "Brasserie" to "Food", "BRASSERIE" to "Food",
        "Cocktail" to "Food", "COCKTAIL BAR" to "Food",
        "Wine Bar" to "Food", "WINE BAR" to "Food",

        // Food Delivery Apps
        "efood" to "Food", "E-FOOD" to "Food", 
        "EFOOD" to "Food", "ONLINE DELIVERY" to "Food",
        "E FOOD SA" to "Food", "EFOOD GR" to "Food",
        "Wolt" to "Food", "WOLT" to "Food", 
        "WOLT GREECE" to "Food", "WOLT ENTERPRISES" to "Food",
        "Box" to "Food", "BOX DELIVERY" to "Food", 
        "BOX NOW" to "Food",
        "Uber Eats" to "Food", "UBER EATS" to "Food", 
        "UBEREATS" to "Food",
        "Glovo" to "Food", "GLOVO" to "Food", 
        "GLOVOAPP" to "Food",
        "Just Eat" to "Food", "JUST EAT" to "Food",
        "Deliveroo" to "Food", "DELIVEROO" to "Food",
        "Doordash" to "Food", "DOORDASH" to "Food",
        "Getir" to "Food", "GETIR" to "Food",
        "Gorillas" to "Food", "GORILLAS" to "Food",
        "Flink" to "Food", "FLINK" to "Food",
        "Delivery" to "Food", "DELIVERY" to "Food",
        "Take away" to "Food", "TAKEAWAY" to "Food",

        // Ice Cream & Desserts
        "Haagen Dazs" to "Food", "HAAGEN DAZS" to "Food",
        "Ben Jerry" to "Food", "BEN JERRYS" to "Food",
        "Baskin Robbins" to "Food", "BASKIN ROBBINS" to "Food",
        "Gelato" to "Food", "GELATO" to "Food",
        "Pagoto" to "Food", "Παγωτό" to "Food",
        "Dodoni" to "Food", "ΔΩΔΩΝΗ" to "Food",
        "Kayak" to "Food", "KAYAK ICECREAM" to "Food",
        "Cremeria" to "Food", "CREMERIA" to "Food",
        "Patisserie" to "Food", "PATISSERIE" to "Food",
        "Zacharoplasteio" to "Food", "Ζαχαροπλαστείο" to "Food",
        "Sweets" to "Food", "ΓΛΥΚΑ" to "Food",
        "Crepe" to "Food", "CREPE" to "Food",
        "Waffle" to "Food", "WAFFLE" to "Food",
        "Churros" to "Food", "CHURROS" to "Food",
        "Donuts" to "Food", "DONUT" to "Food",

        // ═══════════════════════════════════════════════════════════════
        // 🛍️ SHOPPING
        // ═══════════════════════════════════════════════════════════════

        // Inditex Group (Zara parent)
        "Zara" to "Shopping", "ZARA" to "Shopping", 
        "ZARA HELLAS" to "Shopping", "ITX HELLAS" to "Shopping",
        "Pull&Bear" to "Shopping", "PULL AND BEAR" to "Shopping", 
        "PULL&BEAR" to "Shopping",
        "Bershka" to "Shopping", "BERSHKA" to "Shopping", 
        "Stradivarius" to "Shopping", "STRADIVARIUS" to "Shopping",
        "Massimo Dutti" to "Shopping", "MASSIMO DUTTI" to "Shopping",
        "Oysho" to "Shopping", "OYSHO" to "Shopping",
        "Zara Home" to "Shopping", "ZARA HOME" to "Shopping",
        "Uterque" to "Shopping", "UTERQUE" to "Shopping",

        // H&M Group
        "H&M" to "Shopping", "H & M" to "Shopping", 
        "H AND M" to "Shopping", "HENNES" to "Shopping",
        "COS" to "Shopping", "COS STORES" to "Shopping",
        "& Other Stories" to "Shopping", "OTHER STORIES" to "Shopping",
        "Arket" to "Shopping", "ARKET" to "Shopping",
        "Weekday" to "Shopping", "WEEKDAY" to "Shopping",
        "Monki" to "Shopping", "MONKI" to "Shopping",

        // Calzedonia Group
        "Intimissimi" to "Shopping", "INTIMISSIMI" to "Shopping",
        "Calzedonia" to "Shopping", "CALZEDONIA" to "Shopping",
        "Tezenis" to "Shopping", "TEZENIS" to "Shopping",
        "Falconeri" to "Shopping", "FALCONERI" to "Shopping",

        // Fashion - International
        "Mango" to "Shopping", "MANGO" to "Shopping", "MNG" to "Shopping",
        "Uniqlo" to "Shopping", "UNIQLO" to "Shopping",
        "Gap" to "Shopping", "GAP" to "Shopping",
        "Old Navy" to "Shopping", "OLD NAVY" to "Shopping",
        "Banana Republic" to "Shopping", "BANANA REPUBLIC" to "Shopping",
        "Primark" to "Shopping", "PRIMARK" to "Shopping",
        "C&A" to "Shopping", "C AND A" to "Shopping",
        "New Yorker" to "Shopping", "NEW YORKER" to "Shopping",
        "Reserved" to "Shopping", "RESERVED" to "Shopping",
        "Sinsay" to "Shopping", "SINSAY" to "Shopping",
        "House" to "Shopping", "HOUSE BRAND" to "Shopping",
        "Cropp" to "Shopping", "CROPP" to "Shopping",
        "Mohito" to "Shopping", "MOHITO" to "Shopping",
        "Forever 21" to "Shopping", "FOREVER21" to "Shopping",
        "Topshop" to "Shopping", "TOPSHOP" to "Shopping",
        "River Island" to "Shopping", "RIVER ISLAND" to "Shopping",
        "Asos" to "Shopping", "ASOS" to "Shopping",
        "Boohoo" to "Shopping", "BOOHOO" to "Shopping",
        "Shein" to "Shopping", "SHEIN" to "Shopping",
        "Temu" to "Shopping", "TEMU" to "Shopping",
        "Wish" to "Shopping", "WISH COM" to "Shopping",

        // Premium Fashion
        "Tommy Hilfiger" to "Shopping", "TOMMY HILFIGER" to "Shopping",
        "Calvin Klein" to "Shopping", "CALVIN KLEIN" to "Shopping",
        "Ralph Lauren" to "Shopping", "POLO RALPH" to "Shopping",
        "Lacoste" to "Shopping", "LACOSTE" to "Shopping",
        "Hugo Boss" to "Shopping", "HUGO BOSS" to "Shopping",
        "Gant" to "Shopping", "GANT" to "Shopping",
        "Armani" to "Shopping", "ARMANI EXCHANGE" to "Shopping",
        "Michael Kors" to "Shopping", "MICHAEL KORS" to "Shopping",
        "Coach" to "Shopping", "COACH" to "Shopping",
        "Kate Spade" to "Shopping", "KATE SPADE" to "Shopping",
        "Guess" to "Shopping", "GUESS" to "Shopping",
        "Diesel" to "Shopping", "DIESEL" to "Shopping",
        "Replay" to "Shopping", "REPLAY" to "Shopping",
        "Levis" to "Shopping", "LEVIS" to "Shopping", "LEVI STRAUSS" to "Shopping",
        "Wrangler" to "Shopping", "WRANGLER" to "Shopping",
        "Lee" to "Shopping", "LEE JEANS" to "Shopping",

        // Sports Brands
        "Nike" to "Shopping", "NIKE" to "Shopping", 
        "NIKE RETAIL" to "Shopping", "NIKE STORE" to "Shopping",
        "Adidas" to "Shopping", "ADIDAS" to "Shopping",
        "Puma" to "Shopping", "PUMA" to "Shopping", 
        "Reebok" to "Shopping", "REEBOK" to "Shopping",
        "Under Armour" to "Shopping", "UNDER ARMOUR" to "Shopping",
        "New Balance" to "Shopping", "NEW BALANCE" to "Shopping",
        "Asics" to "Shopping", "ASICS" to "Shopping",
        "Converse" to "Shopping", "CONVERSE" to "Shopping",
        "Vans" to "Shopping", "VANS" to "Shopping",
        "Fila" to "Shopping", "FILA" to "Shopping",
        "Champion" to "Shopping", "CHAMPION" to "Shopping",
        "Skechers" to "Shopping", "SKECHERS" to "Shopping",
        "Timberland" to "Shopping", "TIMBERLAND" to "Shopping",
        "Columbia" to "Shopping", "COLUMBIA SPORTSWEAR" to "Shopping",
        "North Face" to "Shopping", "THE NORTH FACE" to "Shopping",
        "Patagonia" to "Shopping", "PATAGONIA" to "Shopping",
        "Helly Hansen" to "Shopping", "HELLY HANSEN" to "Shopping",
        "Jack Wolfskin" to "Shopping", "JACK WOLFSKIN" to "Shopping",
        "Salomon" to "Shopping", "SALOMON" to "Shopping",
        "Arc'teryx" to "Shopping", "ARCTERYX" to "Shopping",

        // Sports Retailers
        "Intersport" to "Shopping", "INTERSPORT" to "Shopping",
        "Cosmos Sport" to "Shopping", "COSMOS SPORT" to "Shopping",
        "Zakcret" to "Shopping", "ZAKCRET" to "Shopping", 
        "Sports Factory" to "Shopping", "SPORTS FACTORY" to "Shopping",
        "Foot Locker" to "Shopping", "FOOTLOCKER" to "Shopping",
        "JD Sports" to "Shopping", "JD SPORTS" to "Shopping",
        "Snipes" to "Shopping", "SNIPES" to "Shopping",
        "Sportsdirect" to "Shopping", "SPORTSDIRECT" to "Shopping",
        "Decathlon" to "Shopping", "DECATHLON" to "Shopping",
        "Athletes Foot" to "Shopping", "ATHLETES FOOT" to "Shopping",
        "Stadium" to "Shopping", "STADIUM" to "Shopping",
        "XXL Sport" to "Shopping", "XXL SPORT" to "Shopping",

        // Department Stores
        "Attica" to "Shopping", "ATTICA" to "Shopping", 
        "ATTICA DEPT" to "Shopping", "ATTICA GOLDEN HALL" to "Shopping",
        "Notos" to "Shopping", "NOTOS GALLERIES" to "Shopping",
        "Fokas" to "Shopping", "FOKAS" to "Shopping",
        "Galeries Lafayette" to "Shopping", "GALERIES LAFAYETTE" to "Shopping",
        "Harrods" to "Shopping", "HARRODS" to "Shopping",
        "Selfridges" to "Shopping", "SELFRIDGES" to "Shopping",
        "Harvey Nichols" to "Shopping", "HARVEY NICHOLS" to "Shopping",
        "El Corte Ingles" to "Shopping", "EL CORTE INGLES" to "Shopping",
        "Printemps" to "Shopping", "PRINTEMPS" to "Shopping",
        "KaDeWe" to "Shopping", "KADEWE" to "Shopping",
        "Breuninger" to "Shopping", "BREUNINGER" to "Shopping",
        "Nordstrom" to "Shopping", "NORDSTROM" to "Shopping",
        "Bloomingdales" to "Shopping", "BLOOMINGDALES" to "Shopping",
        "Macys" to "Shopping", "MACYS" to "Shopping",

        // Outlets
        "Factory Outlet" to "Shopping", "FACTORY OUTLET" to "Shopping",
        "McArthurGlen" to "Shopping", "DESIGNER OUTLET" to "Shopping",
        "MCARTHURGLEN ATHENS" to "Shopping",
        "Outlet" to "Shopping", "OUTLET STORE" to "Shopping",
        "Smart Park" to "Shopping", "SMART PARK" to "Shopping",
        "The Mall Athens" to "Shopping", "THE MALL" to "Shopping",
        "Golden Hall" to "Shopping", "GOLDEN HALL" to "Shopping",
        "Athens Metro Mall" to "Shopping", "METRO MALL" to "Shopping",
        "Mediterranean Cosmos" to "Shopping", "MED COSMOS" to "Shopping",

        // Shoes
        "Kalogirou" to "Shopping", "KALOGIROU" to "Shopping",
        "Tsakiris Mallas" to "Shopping", "TSAKIRIS MALLAS" to "Shopping",
        "Mourtzi" to "Shopping", "MOURTZI" to "Shopping",
        "Migato" to "Shopping", "MIGATO" to "Shopping",
        "Seven" to "Shopping", "SEVEN SHOES" to "Shopping",
        "Topshoes" to "Shopping", "TOPSHOES" to "Shopping",
        "Shoe Cult" to "Shopping", "SHOE CULT" to "Shopping",
        "Sante" to "Shopping", "SANTE" to "Shopping",
        "Bozikis" to "Shopping", "BOZIKIS" to "Shopping",
        "Clarks" to "Shopping", "CLARKS" to "Shopping",
        "Ecco" to "Shopping", "ECCO" to "Shopping",
        "Geox" to "Shopping", "GEOX" to "Shopping",
        "Camper" to "Shopping", "CAMPER" to "Shopping",
        "Birkenstock" to "Shopping", "BIRKENSTOCK" to "Shopping",
        "Crocs" to "Shopping", "CROCS" to "Shopping",
        "Dr Martens" to "Shopping", "DR MARTENS" to "Shopping",
        "UGG" to "Shopping", "UGG" to "Shopping",
        "Stuart Weitzman" to "Shopping", "STUART WEITZMAN" to "Shopping",
        "Jimmy Choo" to "Shopping", "JIMMY CHOO" to "Shopping",

        // Jewelry & Accessories
        "Pandora" to "Shopping", "PANDORA" to "Shopping",
        "Swarovski" to "Shopping", "SWAROVSKI" to "Shopping",
        "Tous" to "Shopping", "TOUS" to "Shopping",
        "Folli Follie" to "Shopping", "FOLLI FOLLIE" to "Shopping",
        "Links of London" to "Shopping", "LINKS OF LONDON" to "Shopping",
        "Thomas Sabo" to "Shopping", "THOMAS SABO" to "Shopping",
        "Trollbeads" to "Shopping", "TROLLBEADS" to "Shopping",
        "Alex and Ani" to "Shopping", "ALEX AND ANI" to "Shopping",
        "Nomination" to "Shopping", "NOMINATION" to "Shopping",
        "Accessorize" to "Shopping", "ACCESSORIZE" to "Shopping",
        "Bijou Brigitte" to "Shopping", "BIJOU BRIGITTE" to "Shopping",
        "Oriflame" to "Shopping", "ORIFLAME" to "Shopping",
        "Rolex" to "Shopping", "ROLEX" to "Shopping",
        "Omega" to "Shopping", "OMEGA WATCHES" to "Shopping",
        "Tag Heuer" to "Shopping", "TAG HEUER" to "Shopping",
        "Longines" to "Shopping", "LONGINES" to "Shopping",
        "Tissot" to "Shopping", "TISSOT" to "Shopping",
        "Casio" to "Shopping", "CASIO" to "Shopping",
        "Swatch" to "Shopping", "SWATCH" to "Shopping",
        "Fossil" to "Shopping", "FOSSIL" to "Shopping",
        "Daniel Wellington" to "Shopping", "DANIEL WELLINGTON" to "Shopping",
        "Jewelry" to "Shopping", "JEWELRY" to "Shopping",
        "Κοσμήματα" to "Shopping", "KOSMIMATA" to "Shopping",
        "Watch" to "Shopping", "WATCH STORE" to "Shopping",

        // ═══════════════════════════════════════════════════════════════
        // 💻 ELECTRONICS & TECH
        // ═══════════════════════════════════════════════════════════════

        // Greek E-commerce & Retail
        "Skroutz" to "Electronics", "SKROUTZ" to "Electronics", 
        "SKROUTZ.GR" to "Electronics", "PAYMENTS SKROUTZ" to "Electronics",
        "SKROUTZ MARKETPLACE" to "Electronics",
        "Public" to "Electronics", "PUBLIC" to "Electronics", 
        "PUBLIC RETAIL" to "Electronics", "PUBLIC.GR" to "Electronics",
        "Plaisio" to "Electronics", "PLAISIO" to "Electronics", 
        "PLAISIO COMPUTERS" to "Electronics",
        "Kotsovolos" to "Electronics", "KOTSOVOLOS" to "Electronics", 
        "DIXONS" to "Electronics", "SOUTH EAST EUROPE" to "Electronics",
        "Media Markt" to "Electronics", "MEDIA MARKT" to "Electronics",
        "Germanos" to "Electronics", "GERMANOS" to "Electronics", 
        "COSMOTE E-VALUE" to "Electronics",
        "E-shop" to "Electronics", "E-SHOP" to "Electronics", 
        "E-SHOP.GR" to "Electronics",
        "You.gr" to "Electronics", "YOU.GR" to "Electronics",
        "BestPrice" to "Electronics", "BESTPRICE" to "Electronics",
        "Electronet" to "Electronics", "ELECTRONET" to "Electronics",
        "Mediamarkt" to "Electronics", "MEDIAMARKT" to "Electronics",
        "Kaizer" to "Electronics", "KAIZER" to "Electronics",
        "Info Quest" to "Electronics", "INFOQUEST" to "Electronics",
        "Multirama" to "Electronics", "MULTIRAMA" to "Electronics",

        // Global E-commerce
        "Amazon" to "Electronics", "AMAZON" to "Electronics", 
        "AMZN" to "Electronics", "AMAZON.DE" to "Electronics",
        "AMAZON.CO.UK" to "Electronics", "AMAZON.ES" to "Electronics",
        "AMAZON.FR" to "Electronics", "AMAZON.IT" to "Electronics",
        "AMAZON.COM" to "Electronics", "AWS" to "Electronics",
        "AMAZON PRIME" to "Subscriptions", "PRIME VIDEO" to "Subscriptions",
        "Ebay" to "Electronics", "EBAY" to "Electronics", 
        "PAYPAL EBAY" to "Electronics",
        "AliExpress" to "Electronics", "ALIEXPRESS" to "Electronics",
        "ALIBABA" to "Electronics",
        "Banggood" to "Electronics", "BANGGOOD" to "Electronics",
        "Gearbest" to "Electronics", "GEARBEST" to "Electronics",
        "DHgate" to "Electronics", "DHGATE" to "Electronics",
        "JD.com" to "Electronics", "JD COM" to "Electronics",
        "Newegg" to "Electronics", "NEWEGG" to "Electronics",
        "CDW" to "Electronics",

        // Apple
        "Apple" to "Electronics", "APPLE STORE" to "Electronics", 
        "APPLE.COM" to "Electronics", "APPLE INC" to "Electronics",
        "Apple Store" to "Electronics", "APPLE RETAIL" to "Electronics",
        "iTunes" to "Subscriptions", "ITUNES" to "Subscriptions",
        "App Store" to "Subscriptions", "APPLE.COM/BILL" to "Subscriptions",

        // Other Brands
        "Samsung" to "Electronics", "SAMSUNG" to "Electronics",
        "SAMSUNG ELECTRONICS" to "Electronics",
        "Xiaomi" to "Electronics", "MI STORE" to "Electronics", 
        "XIAOMI" to "Electronics",
        "Huawei" to "Electronics", "HUAWEI" to "Electronics",
        "OnePlus" to "Electronics", "ONEPLUS" to "Electronics",
        "Oppo" to "Electronics", "OPPO" to "Electronics",
        "Vivo" to "Electronics", "VIVO" to "Electronics",
        "Realme" to "Electronics", "REALME" to "Electronics",
        "Google Store" to "Electronics", "GOOGLE STORE" to "Electronics",
        "Google" to "Subscriptions", "GOOGLE" to "Subscriptions",
        "Microsoft" to "Electronics", "MICROSOFT STORE" to "Electronics",
        "Sony" to "Electronics", "SONY" to "Electronics", 
        "SONY CENTER" to "Electronics",
        "LG" to "Electronics", "LG ELECTRONICS" to "Electronics",
        "Philips" to "Electronics", "PHILIPS" to "Electronics",
        "Panasonic" to "Electronics", "PANASONIC" to "Electronics",
        "Bose" to "Electronics", "BOSE" to "Electronics",
        "Bang Olufsen" to "Electronics", "BANG OLUFSEN" to "Electronics",
        "Harman Kardon" to "Electronics", "HARMAN" to "Electronics",
        "JBL" to "Electronics", "JBL" to "Electronics",
        "Beats" to "Electronics", "BEATS" to "Electronics",
        "Sennheiser" to "Electronics", "SENNHEISER" to "Electronics",
        "DJI" to "Electronics", "DJI STORE" to "Electronics",
        "GoPro" to "Electronics", "GOPRO" to "Electronics",
        "Canon" to "Electronics", "CANON" to "Electronics",
        "Nikon" to "Electronics", "NIKON" to "Electronics",
        "Sony Alpha" to "Electronics", "SONY ALPHA" to "Electronics",
        "Fujifilm" to "Electronics", "FUJIFILM" to "Electronics",
        "Olympus" to "Electronics", "OLYMPUS" to "Electronics",
        "Nintendo" to "Electronics", "NINTENDO" to "Electronics",
        "PlayStation" to "Electronics", "PLAYSTATION STORE" to "Electronics",
        "Xbox" to "Electronics", "XBOX STORE" to "Electronics",

        // Telecom Shops
        "Cosmote" to "Electronics", "COSMOTE" to "Electronics",
        "COSMOTE SHOP" to "Electronics",
        "Vodafone" to "Electronics", "VODAFONE SHOP" to "Electronics",
        "Wind" to "Electronics", "WIND SHOP" to "Electronics",
        "Nova" to "Electronics", "NOVA SHOP" to "Electronics",
        "Phone" to "Electronics", "PHONE STORE" to "Electronics",
        "Mobile" to "Electronics", "MOBILE SHOP" to "Electronics",
        "Service Mobile" to "Electronics", "REPAIR SHOP" to "Electronics",
        "iRepair" to "Electronics", "IREPAIR" to "Electronics",

        // ═══════════════════════════════════════════════════════════════
        // 📺 SUBSCRIPTIONS - Streaming, Cloud, Software, AI
        // ═══════════════════════════════════════════════════════════════

        // Video Streaming
        "Netflix" to "Subscriptions", "NETFLIX" to "Subscriptions", 
        "NETFLIX.COM" to "Subscriptions",
        "Disney+" to "Subscriptions", "DISNEY PLUS" to "Subscriptions", 
        "DISNEY+" to "Subscriptions", "DISNEYPLUS" to "Subscriptions",
        "HBO" to "Subscriptions", "HBO MAX" to "Subscriptions", 
        "WARNER BROS" to "Subscriptions",
        "Hulu" to "Subscriptions", "HULU" to "Subscriptions",
        "Amazon Prime" to "Subscriptions", "PRIME VIDEO" to "Subscriptions", 
        "AMAZONPRIME" to "Subscriptions",
        "Apple TV" to "Subscriptions", "APPLE TV+" to "Subscriptions",
        "Paramount+" to "Subscriptions", "PARAMOUNT" to "Subscriptions",
        "Peacock" to "Subscriptions", "PEACOCK TV" to "Subscriptions",
        "Discovery+" to "Subscriptions", "DISCOVERY PLUS" to "Subscriptions",
        "Rakuten TV" to "Subscriptions", "RAKUTEN" to "Subscriptions",
        "Mubi" to "Subscriptions", "MUBI" to "Subscriptions",

        // Greek TV & Streaming
        "Cosmote TV" to "Subscriptions", "COSMOTE TV" to "Subscriptions",
        "Nova" to "Subscriptions", "NOVA" to "Subscriptions", 
        "Eon" to "Subscriptions", "EON TV" to "Subscriptions",
        "Vodafone TV" to "Subscriptions", "VODAFONE TV" to "Subscriptions",
        "Ertflix" to "Subscriptions", "ERTFLIX" to "Subscriptions",
        "Ant1+" to "Subscriptions", "ANT1 PLUS" to "Subscriptions",
        "Cinobo" to "Subscriptions", "CINOBO" to "Subscriptions",

        // Music Streaming
        "Spotify" to "Subscriptions", "SPOTIFY" to "Subscriptions", 
        "SPOTIFY LUXEMBOURG" to "Subscriptions",
        "Apple Music" to "Subscriptions", "APPLE.COM/BILL" to "Subscriptions",
        "Youtube Music" to "Subscriptions", "YOUTUBE PREMIUM" to "Subscriptions",
        "Deezer" to "Subscriptions", "DEEZER" to "Subscriptions",
        "Tidal" to "Subscriptions", "TIDAL" to "Subscriptions",
        "Soundcloud" to "Subscriptions", "SOUNDCLOUD" to "Subscriptions",
        "Qobuz" to "Subscriptions", "QOBUZ" to "Subscriptions",

        // Cloud & Storage
        "Google One" to "Subscriptions", "GOOGLE ONE" to "Subscriptions", 
        "GOOGLE STORAGE" to "Subscriptions", "GOOGLE CLOUD" to "Subscriptions",
        "iCloud" to "Subscriptions", "APPLE ICLOUD" to "Subscriptions",
        "Dropbox" to "Subscriptions", "DROPBOX" to "Subscriptions",
        "OneDrive" to "Subscriptions", "MICROSOFT STORAGE" to "Subscriptions",
        "Box.com" to "Subscriptions", "BOX" to "Subscriptions",
        "Mega.nz" to "Subscriptions", "MEGA" to "Subscriptions",
        "Nextcloud" to "Subscriptions", "NEXTCLOUD" to "Subscriptions",

        // Productivity & Software
        "Microsoft 365" to "Subscriptions", "OFFICE 365" to "Subscriptions", 
        "MSFT" to "Subscriptions",
        "Adobe" to "Subscriptions", "ADOBE" to "Subscriptions", 
        "CREATIVE CLOUD" to "Subscriptions",
        "Canva" to "Subscriptions", "CANVA" to "Subscriptions",
        "Evernote" to "Subscriptions", "EVERNOTE" to "Subscriptions",
        "Notion" to "Subscriptions", "NOTION" to "Subscriptions",
        "Slack" to "Subscriptions", "SLACK" to "Subscriptions",
        "Zoom" to "Subscriptions", "ZOOM.US" to "Subscriptions",
        "Grammarly" to "Subscriptions", "GRAMMARLY" to "Subscriptions",
        "LinkedIn" to "Subscriptions", "LINKEDIN PREMIUM" to "Subscriptions",

        // VPN & Security
        "NordVPN" to "Subscriptions", "NORDVPN" to "Subscriptions",
        "ExpressVPN" to "Subscriptions", "EXPRESSVPN" to "Subscriptions",
        "Surfshark" to "Subscriptions", "SURFSHARK" to "Subscriptions",
        "CyberGhost" to "Subscriptions", "CYBERGHOST" to "Subscriptions",
        "Bitdefender" to "Subscriptions", "BITDEFENDER" to "Subscriptions",
        "Norton" to "Subscriptions", "NORTON" to "Subscriptions",
        "Avast" to "Subscriptions", "AVAST" to "Subscriptions",
        "Malwarebytes" to "Subscriptions", "MALWAREBYTES" to "Subscriptions",
        "1Password" to "Subscriptions", "1PASSWORD" to "Subscriptions",
        "LastPass" to "Subscriptions", "LASTPASS" to "Subscriptions",
        "Dashlane" to "Subscriptions", "DASHLANE" to "Subscriptions",

        // Gaming
        "Steam" to "Subscriptions", "STEAMGAMES" to "Subscriptions", 
        "VALVE" to "Subscriptions", "STEAM PURCHASE" to "Subscriptions",
        "Epic Games" to "Subscriptions", "EPIC GAMES" to "Subscriptions",
        "PlayStation" to "Subscriptions", "PLAYSTATION" to "Subscriptions", 
        "PSN" to "Subscriptions", "PS PLUS" to "Subscriptions", 
        "SONY NETWORK" to "Subscriptions",
        "Xbox" to "Subscriptions", "XBOX" to "Subscriptions", 
        "MICROSOFT XBOX" to "Subscriptions", "GAME PASS" to "Subscriptions",
        "Nintendo" to "Subscriptions", "NINTENDO ONLINE" to "Subscriptions",
        "EA Play" to "Subscriptions", "EA" to "Subscriptions",
        "Ubisoft+" to "Subscriptions", "UBISOFT" to "Subscriptions",
        "Blizzard" to "Subscriptions", "BATTLE.NET" to "Subscriptions",
        "Roblox" to "Subscriptions", "ROBLOX" to "Subscriptions",

        // Streaming & Social
        "Twitch" to "Subscriptions", "TWITCH" to "Subscriptions",
        "Discord" to "Subscriptions", "DISCORD" to "Subscriptions", 
        "NITRO" to "Subscriptions",
        "Patreon" to "Subscriptions", "PATREON" to "Subscriptions",
        "Substack" to "Subscriptions", "SUBSTACK" to "Subscriptions",
        "OnlyFans" to "Subscriptions", "ONLYFANS" to "Subscriptions",

        // AI & Dev Tools
        "ChatGPT" to "Subscriptions", "OPENAI" to "Subscriptions",
        "Claude" to "Subscriptions", "ANTHROPIC" to "Subscriptions",
        "Midjourney" to "Subscriptions", "MIDJOURNEY" to "Subscriptions",
        "GitHub" to "Subscriptions", "GITHUB" to "Subscriptions", 
        "COPILLOT" to "Subscriptions",
        "DigitalOcean" to "Subscriptions", "DIGITALOCEAN" to "Subscriptions",
        "Cloudflare" to "Subscriptions", "CLOUDFLARE" to "Subscriptions",
        "Heroku" to "Subscriptions", "HEROKU" to "Subscriptions",
        "Vercel" to "Subscriptions", "VERCEL" to "Subscriptions",

        // Education & Others
        "Duolingo" to "Subscriptions", "DUOLINGO" to "Subscriptions",
        "Udemy" to "Subscriptions", "UDEMY" to "Subscriptions",
        "Coursera" to "Subscriptions", "COURSERA" to "Subscriptions",
        "Masterclass" to "Subscriptions", "MASTERCLASS" to "Subscriptions",
        "Babbel" to "Subscriptions", "BABBEL" to "Subscriptions",
        "Fitness App" to "Subscriptions", "GYMSHARK" to "Subscriptions",
        "Strava" to "Subscriptions", "STRAVA" to "Subscriptions",
        "Tinder" to "Subscriptions", "TINDER" to "Subscriptions",
        "Bumble" to "Subscriptions", "BUMBLE" to "Subscriptions",

        // ═══════════════════════════════════════════════════════════════
        // 💡 UTILITIES - Bills, Services, Telecom
        // ═══════════════════════════════════════════════════════════════

        // Electricity
        "DEI" to "Utilities", "ΔΕΗ" to "Utilities", 
        "DIMOSIA EPICHEIRISI" to "Utilities", "DEH" to "Utilities",
        "Heron" to "Utilities", "IRON" to "Utilities", 
        "ΗΡΩΝ" to "Utilities", "HERON ENERGY" to "Utilities",
        "Protergia" to "Utilities", "PROTERGIA" to "Utilities", 
        "MYTILINEOS" to "Utilities",
        "Elpedison" to "Utilities", "ELPEDISON" to "Utilities",
        "Volton" to "Utilities", "VOLTON" to "Utilities",
        "NRG" to "Utilities", "NRG TRADING" to "Utilities",
        "Zenith" to "Utilities", "ZENITH" to "Utilities",
        "Watt+Volt" to "Utilities", "WATT AND VOLT" to "Utilities", 
        "WATT&VOLT" to "Utilities",
        "Fysiko Aerio" to "Utilities", "ΦΥΣΙΚΟ ΑΕΡΙΟ" to "Utilities",
        "Solar" to "Utilities", "SOLAR ENERGY" to "Utilities",

        // Water
        "EYDAP" to "Utilities", "ΕΥΔΑΠ" to "Utilities", 
        "NERO" to "Utilities", "WATER BILL" to "Utilities",
        "EYATH" to "Utilities", "ΕΥΑΘ" to "Utilities",

        // Gas & Heating
        "Fysiko Aerio" to "Utilities", "AERIO" to "Utilities", 
        "EPA" to "Utilities", "GAS BILL" to "Utilities",
        "Heating Oil" to "Utilities", "PETRELAIO" to "Utilities",

        // Telecom - Fixed & Mobile
        "Cosmote" to "Utilities", "COSMOTE" to "Utilities", 
        "OTE" to "Utilities", "ΟΤΕ" to "Utilities",
        "Vodafone" to "Utilities", "VODAFONE" to "Utilities", 
        "VODAFONE PANAFON" to "Utilities",
        "Wind" to "Utilities", "WIND" to "Utilities",
        "Nova" to "Utilities", "NOVA" to "Utilities", 
        "NOVA TELECOMB" to "Utilities",
        "Inalan" to "Utilities", "INALAN" to "Utilities",
        "Cyta" to "Utilities", "CYTA" to "Utilities",

        // Others
        "Koinoxrista" to "Utilities", "Κοινόχρηστα" to "Utilities", 
        "Polytechneio" to "Utilities", "SHARED EXPENSES" to "Utilities",
        "Cleaning Service" to "Utilities", "KATHARIOTHTA" to "Utilities",
        "Waste" to "Utilities", "DIMOS" to "Utilities",

        // ═══════════════════════════════════════════════════════════════
        // 🏥 HEALTH & FITNESS
        // ═══════════════════════════════════════════════════════════════

        // Pharmacies
        "Pharmacy" to "Health", "PHARMACY" to "Health", 
        "Φαρμακείο" to "Health", "Farmakeio" to "Health", 
        "DRUGSTORE" to "Health", "PHARME" to "Health",

        // Medical Services
        "Doctor" to "Health", "DOCTOR" to "Health", 
        "Γιατρός" to "Health", "Iatros" to "Health",
        "Dentist" to "Health", "Οδοντίατρος" to "Health", 
        "Odontiatros" to "Health",
        "Hospital" to "Health", "Nosokomeio" to "Health", 
        "Νοσοκομείο" to "Health",
        "Clinic" to "Health", "Κλινική" to "Health",
        "Diagnostic" to "Health", "ΔΙΑΓΝΩΣΤΙΚΟ" to "Health",

        // Centers & Platforms
        "Iatropolis" to "Health", "IATROPOLIS" to "Health",
        "Bioiatriki" to "Health", "BIOIATRIKI" to "Health",
        "Affidea" to "Health", "AFFIDEA" to "Health",
        "Euromedica" to "Health", "EUROMEDICA" to "Health",
        "Doctoranytime" to "Health", "DOCTORANYTIME" to "Health",

        // Specialists
        "Eye Clinic" to "Health", "Optical" to "Health", 
        "Οπτικά" to "Health", "Optika" to "Health",
        "Psychologist" to "Health", "Ψυχολόγος" to "Health",
        "Physiotherapy" to "Health", "Φυσικοθεραπεία" to "Health",

        // Fitness
        "Gym" to "Fitness", "GYM" to "Fitness", 
        "Gymnastirio" to "Fitness", "Γυμναστήριο" to "Fitness",
        "Yava" to "Fitness", "YAVA" to "Fitness",
        "Planet Fitness" to "Fitness", "Alterlife" to "Fitness", 
        "Holmes Place" to "Fitness",
        "Yoga" to "Fitness", "Pilates" to "Fitness", 
        "Crossfit" to "Fitness",
        "Sports Club" to "Fitness", "ΑΘΛΗΤΙΚΟΣ" to "Fitness",
        "Swimming" to "Fitness", "Κολυμβητήριο" to "Fitness",

        // ═══════════════════════════════════════════════════════════════
        // 🎬 ENTERTAINMENT
        // ═══════════════════════════════════════════════════════════════
        "Village Cinemas" to "Entertainment", "VILLAGE" to "Entertainment",
        "Odeon" to "Entertainment", "Ster Cinemas" to "Entertainment",
        "SNFCC" to "Entertainment", "STAVROS NIARCHOS" to "Entertainment",
        "Technopolis" to "Entertainment", "Ticketmaster" to "Entertainment",
        "Viva.gr" to "Entertainment", "VIVA" to "Entertainment",
        "Eventbrite" to "Entertainment", "Allou" to "Entertainment",
        "Kidom" to "Entertainment", "Escape Room" to "Entertainment",
        "Bowling" to "Entertainment", "Billiards" to "Entertainment",
        "Arcade" to "Entertainment", "Museum" to "Entertainment",
        "Μουσείο" to "Entertainment", "Theater" to "Entertainment",
        "Θέατρο" to "Entertainment", "Concert" to "Entertainment",

        // ═══════════════════════════════════════════════════════════════
        // 🏠 HOME & SERVICES
        // ═══════════════════════════════════════════════════════════════
        "IKEA" to "Shopping", // IKEA is Shopping but also Home
        "Leroy Merlin" to "Shopping", "S.G.B. AE" to "Shopping",
        "Praktiker" to "Shopping", "JYSK" to "Home",
        "BricoMarche" to "Home", "Maisons du Monde" to "Home",
        "Media Strom" to "Home", "Coco-mat" to "Home",
        "Plumber" to "Home", "Υδραυλικός" to "Home",
        "Electrician" to "Home", "Ηλεκτρολόγος" to "Home",
        "Cleaner" to "Home", "Καθαρίστρια" to "Home",
        "Pest Control" to "Home", "Locksmith" to "Home",
        "Moving" to "Home", "Furniture" to "Home",

        // ═══════════════════════════════════════════════════════════════
        // 💄 BEAUTY & PERSONAL CARE
        // ═══════════════════════════════════════════════════════════════
        "Sephora" to "Shopping", "SEPHORA" to "Shopping",
        "Hondos Center" to "Shopping", "HONDOS" to "Shopping",
        "Gallerie de Beaute" to "Shopping", "MAC" to "Shopping",
        "Hair Salon" to "Beauty", "Barber" to "Beauty",
        "Nail Salon" to "Beauty", "Spa" to "Beauty",
        "Waxing" to "Beauty", "The Body Shop" to "Beauty",
        "L'Occitane" to "Beauty", "Kiehl's" to "Beauty",
        "Rituals" to "Beauty", "Lush" to "Beauty",
        "Yves Rocher" to "Beauty", "Apivita" to "Beauty",
        "Korres" to "Beauty",

        // ═══════════════════════════════════════════════════════════════
        // ⚖️ LEGAL & GOVERNMENT
        // ═══════════════════════════════════════════════════════════════
        "EFKA" to "Legal & Gov", "ΕΦΚΑ" to "Legal & Gov",
        "AADE" to "Legal & Gov", "ΑΑΔΕ" to "Legal & Gov",
        "KEA" to "Legal & Gov", "Notary" to "Legal & Gov",
        "Lawyer" to "Legal & Gov", "Δικηγόρος" to "Legal & Gov",
        "Accountant" to "Legal & Gov", "Λογιστής" to "Legal & Gov",
        "Translation" to "Legal & Gov", "Certificate" to "Legal & Gov",
        "Driving License" to "Legal & Gov", "Paravolo" to "Legal & Gov",
        "ΠΑΡΑΒΟΛΟ" to "Legal & Gov", "TAXISNET" to "Legal & Gov",

        // ═══════════════════════════════════════════════════════════════
        // 🐾 PETS
        // ═══════════════════════════════════════════════════════════════
        "Pet City" to "Pets", "PET CITY" to "Pets",
        "Pet Shop" to "Pets", "Pet" to "Pets",
        "Vet" to "Pets", "Ktiniatros" to "Pets",
        "Animal" to "Pets", "Zooplus" to "Pets",
        "Grooming" to "Pets",

        // ═══════════════════════════════════════════════════════════════
        // 🎓 EDUCATION & BOOKS
        // ═══════════════════════════════════════════════════════════════
        "Udemy" to "Education", "Coursera" to "Education",
        "Book" to "Education", "Bookstore" to "Education",
        "Vivlio" to "Education", "Βιβλιοπωλείο" to "Education",
        "Ianos" to "Education", "Politeia" to "Education",
        "Evripidis" to "Education", "Public" to "Electronics", // Note: Public is also books, but mostly Electronics in categorization
        "School" to "Education", "University" to "Education",
        "Tuition" to "Education", "Didaktra" to "Education",

        // ═══════════════════════════════════════════════════════════════
        // 🏦 BANKING & FEES
        // ═══════════════════════════════════════════════════════════════
        "Revolut" to "Banking", "REVOLUT" to "Banking",
        "PayPal" to "Banking", "PAYPAL" to "Banking",
        "Curve" to "Banking", "Wise" to "Banking",
        "Alpha Bank" to "Banking", "Eurobank" to "Banking",
        "Piraeus" to "Banking", "Ethniki" to "Banking",
        "Commission" to "Banking", "Fee" to "Banking",
        "Interest" to "Banking",

        // ═══════════════════════════════════════════════════════════════
        // 🧸 KIDS & BABY
        // ═══════════════════════════════════════════════════════════════
        "Jumbo" to "Shopping", "Moustakas" to "Shopping",
        "DPAM" to "Kids", "Orchestra" to "Kids",
        "Lego Store" to "Kids", "LEGO" to "Kids",
        "Disney Store" to "Kids", "Hamleys" to "Kids",
        "Smyths" to "Kids", "Mothercare" to "Kids",
        "Baby" to "Kids", "Toys" to "Shopping"
    )

    // Additional mapping for normalized uppercase keys to capture variations
    fun getExpandedMap(): Map<String, String> {
        return merchantToCategoryMap.mapKeys { it.key.uppercase() }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\AnalyticsRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositoryanalyticsrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.CategoryBreakdown
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.yourname.expensetracker.data.database.entity.Category

data class SpendingSummary(
    val totalSpent: Double,
    val previousTotalSpent: Double?,
    val changePercent: Float?,
    val dailyHistory: List<Float>,
    val previousDailyHistory: List<Float>,
    val transactionCount: Int
)

@Singleton
class AnalyticsRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryRepository: com.yourname.expensetracker.data.repository.CategoryRepository
) {

    /**
     * getSpendingSummary - Returns a comprehensive summary of spending for the given period.
     * Includes current total, previous period total, percent change, and daily trend.
     */
    fun getSpendingSummary(start: Long, end: Long): Flow<SpendingSummary> {
        val periodLength = end - start
        val previousStart = start - periodLength
        val previousEnd = start

        return combine(
            expenseDao.getExpensesByTypeBetweenFlow(start, end, TransactionType.PURCHASE.name),
            expenseDao.getExpensesByTypeBetweenFlow(previousStart, previousEnd, TransactionType.PURCHASE.name)
        ) { currentPurchases, previousPurchases ->

            val totalSpent = currentPurchases.sumOf { it.amount }
            val previousTotal = previousPurchases.sumOf { it.amount }

            val changePercent = if (previousTotal > 0) {
                ((totalSpent - previousTotal) / previousTotal * 100).toFloat()
            } else null

            // Generate Daily History (Trend)
            // Determine number of days to plot
            val days = ((end - start) / 86400000L).toInt().coerceAtLeast(1)
            val dailyHistory = DoubleArray(days)

            val startOfDay = TimePeriodUtils.getStartOfDay(start)

            currentPurchases.forEach { expense ->
                val dayIndex = ((expense.date - startOfDay) / 86400000L).toInt()
                if (dayIndex in 0 until days) {
                    dailyHistory[dayIndex] += expense.amount
                }
            }

            // Previous History
            val prevDays = ((previousEnd - previousStart) / 86400000L).toInt().coerceAtLeast(1)
            val previousDailyHistory = DoubleArray(prevDays)
            val prevStartOfDay = TimePeriodUtils.getStartOfDay(previousStart)

            previousPurchases.forEach { expense ->
                val dayIndex = ((expense.date - prevStartOfDay) / 86400000L).toInt()
                if (dayIndex in 0 until prevDays) {
                    previousDailyHistory[dayIndex] += expense.amount
                }
            }

            // Convert to cumulative or just daily? 
            // SpendingTrendChart usually expects cumulative for "pace" or daily for "bars". 
            // Existing HomeViewModel uses cumulative. Existing AnalyticsViewModel uses daily totals.
            // Let's return Daily Totals here, UI can accumulate if needed.

            SpendingSummary(
                totalSpent = totalSpent,
                previousTotalSpent = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                dailyHistory = dailyHistory.map { it.toFloat() },
                previousDailyHistory = previousDailyHistory.map { it.toFloat() },
                transactionCount = currentPurchases.size
            )
        }
    }

    /**
     * getCategoryBreakdown - Returns a list of categories sorted by spending amount.
     */
    fun getCategoryBreakdown(start: Long, end: Long): Flow<List<CategoryBreakdown>> {
        return combine(
             expenseDao.getExpensesByTypeBetweenFlow(start, end, TransactionType.PURCHASE.name),
             categoryRepository.allCategories
        ) { purchases, categories ->
            val totalSpent = purchases.sumOf { it.amount }
            val categoryMap = categories.associateBy { it.id }

            purchases.groupBy { it.categoryId }
                .mapNotNull { (catId, exps) ->
                    val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                    val catTotal = exps.sumOf { it.amount }

                    CategoryBreakdown(
                        category = cat,
                        total = catTotal,
                        count = exps.size,
                        percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                    )
                }
                .sortedByDescending { it.total }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetMonitor: BudgetMonitor
) {
    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()

    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        // We fetch the last 13 months to cover yearly budgets + rollover
        val thirteenMonthsAgo = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.MONTH, -13)
        }.timeInMillis

        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow(),
            expenseDao.getExpensesBetweenFlow(thirteenMonthsAgo, System.currentTimeMillis() + 86400000) // +1 day for safety
        ) { budgets, categories, allExpenses ->
            val purchases = allExpenses.filter { it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE }
            val categoryMap = categories.associateBy { it.id }

            budgets.map { budget ->
                val window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)

                fun getSpentInRange(start: Long, end: Long): Double {
                    return purchases
                        .filter { 
                            (budget.categoryId == null || it.categoryId == budget.categoryId) && 
                            it.date >= start && it.date < end 
                        }
                        .sumOf { it.amount }
                }

                val spent = getSpentInRange(window.first, window.second)
                var limit = budget.amount

                // LOG-002: Implement Compounding Rollover - BUG-2 FIX
                if (budget.rollover) {
                    // we compute this by iterating forward from the budget's first period
                    val budgetFirstStart = budget.startDate
                    var movingWindow = budgetMonitor.calculatePeriodWindow(budget.period, budgetFirstStart)
                    var effectiveLimit = budget.amount

                    // Iterate forward until we reach the previous period of the current window
                    while (movingWindow.second <= window.first) {
                        val spentInPeriod = getSpentInRange(movingWindow.first, movingWindow.second)
                        val surplus = (effectiveLimit - spentInPeriod).coerceAtLeast(0.0)
                        effectiveLimit = budget.amount + surplus

                        // Move to next period
                        val nextStart = movingWindow.second
                        movingWindow = budgetMonitor.calculatePeriodWindow(budget.period, nextStart)
                    }
                    limit = effectiveLimit
                }

                val percent = if (limit > 0) (spent / limit).toFloat() else 0f
                val remaining = (limit - spent).coerceAtLeast(0.0)

                val health = when {
                    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
                    percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                    percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }

                BudgetStatus(
                    budget = budget.copy(amount = limit), // Show effective limit
                    category = categoryMap[budget.categoryId],
                    spentAmount = spent,
                    remainingAmount = remaining,
                    percentUsed = percent,
                    healthStatus = health,
                    periodStart = window.first,
                    periodEnd = window.second
                )
            }
        }
    }

    suspend fun addBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Long> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            if (budget.startDate <= 0) throw IllegalArgumentException("Invalid budget start date")
            val id = budgetDao.insert(budget)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(id)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to add budget", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to add budget")
        }
    }

    suspend fun updateBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            if (budget.amount <= 0.0) throw IllegalArgumentException("Budget amount must be greater than zero")
            // Reset notifications when budget is edited so user gets fresh alerts (BUG-7 Fix)
            val resetBudget = budget.copy(
                lastWarningNotifiedAt = null,
                lastCriticalNotifiedAt = null,
                lastExceededNotifiedAt = null
            )
            budgetDao.update(resetBudget)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to update budget ${budget.id}", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to update budget")
        }
    }

    suspend fun deleteBudget(budget: Budget): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.delete(budget)
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete budget ${budget.id}", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete budget")
        }
    }

    suspend fun toggleBudget(id: Long, isActive: Boolean): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.setActive(id, isActive)
            budgetMonitor.checkBudgets()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to toggle budget $id", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to toggle budget")
        }
    }

    suspend fun deleteAll(): com.yourname.expensetracker.domain.model.Result<Unit> {
        return try {
            budgetDao.deleteAll()
            com.yourname.expensetracker.domain.model.Result.Success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("BudgetRepository", "Failed to delete all budgets", e)
            com.yourname.expensetracker.domain.model.Result.Error(e, "Failed to delete all budgets")
        }
    }

    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()

        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()

        val now = System.currentTimeMillis()
        val oldestDate = expenseDao.getOldestExpenseDate() ?: now

        // Use up to 3 months of history, but at least 1 month if available
        // If data is less than 15 days, results might be unreliable, but we'll try to extrapolate conservatively
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        val effectiveStart = maxOf(oldestDate, threeMonthsAgo)

        val daysDiff = ((now - effectiveStart) / (24 * 60 * 60 * 1000)).coerceAtLeast(1)

        // If we have very little data (e.g. < 7 days), skip suggestions to avoid noise (LOG-010)
        if (daysDiff < 7) return emptyList()

        val monthsDivisor = daysDiff / 30.0

        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue

            val spent = expenseDao.getCategorySpentInPeriod(category.id, effectiveStart, now)

            // Calculate monthly average
            val monthlyAvg = if (monthsDivisor > 0) spent / monthsDivisor else 0.0

            // Only suggest if significant spend (> €20/month)
            if (monthlyAvg > 20.0) { 
                suggestions.add(
                    BudgetSuggestion(
                        categoryId = category.id,
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        // increase buffer to 20% (LOG-016)
                        suggestedAmount = (monthlyAvg * 1.2).coerceAtLeast(20.0), 
                        basedOnMonths = Math.round(monthsDivisor).toInt().coerceAtLeast(1),
                        reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                    )
                )
            }
        }
        return suggestions.sortedByDescending { it.suggestedAmount }.take(3)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()

    suspend fun ensureDefaultCategories() = withContext(Dispatchers.IO) {
        try {
            if (categoryDao.getCount() == 0) {
                // Seed Categories
                val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
                categoryDao.insertAll(defaults)

                // Seed Merchant Dictionary
                // We need to resolve Category IDs first to map names to IDs
                val categories = categoryDao.getAllFlow().first() // Use flow first emission or simple get

                // Map: "Groceries" -> 1, "Transport" -> 2
                val categoryIdMap = categories.associate { it.name to it.id }

                val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
                val merchantEntities = merchantMap.mapNotNull { (merchant, categoryName) ->
                   val catId = categoryIdMap[categoryName]
                   if (catId != null) {
                       MerchantCategory(merchantPattern = merchant, categoryId = catId)
                   } else {
                       null
                   }
                }
                if (merchantEntities.isNotEmpty()) {
                    // We need a bulk insert for speed
                    merchantCategoryDao.insertAll(merchantEntities)
                }
            } else {
                // BUG-012 Fix: Ensure "Uncategorized" exists even for existing users
                val categories = categoryDao.getAllFlow().first()
                if (categories.none { it.name.equals("Uncategorized", ignoreCase = true) }) {
                    val uncategorized = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
                        .find { it.name == "Uncategorized" }
                    if (uncategorized != null) {
                        categoryDao.insert(uncategorized)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CategoryRepository", "Failed to seed default categories", e)
        }
    }

    suspend fun addCategory(name: String, icon: String, color: String) = withContext(Dispatchers.IO) {
        val category = Category(name = name, icon = icon, color = color)
        categoryDao.insert(category)
    }

    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) = withContext(Dispatchers.IO) {
        val normalized = categorizationEngine.normalize(merchantName)
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\DashboardRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositorydashboardrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.yourname.expensetracker.data.database.model.DashboardWidgetConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class DashboardRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("dashboard_prefs", Context.MODE_PRIVATE)

    private val _configFlow = MutableStateFlow(getDashboardConfig())
    val configFlow: StateFlow<List<DashboardWidgetConfig>> = _configFlow.asStateFlow()

    fun getDashboardConfig(): List<DashboardWidgetConfig> {
        val json = prefs.getString("layout_config", null) ?: return getDefaultConfig()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<DashboardWidgetConfig>()
            val savedIds = mutableSetOf<String>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                savedIds.add(id)
                list.add(
                    DashboardWidgetConfig(
                        id = id,
                        order = obj.getInt("order"),
                        isVisible = obj.optBoolean("isVisible", true)
                    )
                )
            }

            // Merge new defaults that aren't in saved config
            val defaults = getDefaultConfig()
            var nextOrder = (list.maxOfOrNull { it.order } ?: 0) + 1

            defaults.forEach { def ->
                if (def.id !in savedIds) {
                    list.add(def.copy(order = nextOrder++))
                }
            }

            list.sortedBy { it.order }
        } catch (e: Exception) {
            getDefaultConfig()
        }
    }

    fun saveDashboardConfig(config: List<DashboardWidgetConfig>) {
        val array = JSONArray()
        config.forEach {
            val obj = JSONObject()
            obj.put("id", it.id)
            obj.put("order", it.order)
            obj.put("isVisible", it.isVisible)
            array.put(obj)
        }
        prefs.edit().putString("layout_config", array.toString()).apply()
        _configFlow.value = config
    }

    private fun getDefaultConfig(): List<DashboardWidgetConfig> {
        return listOf(
            DashboardWidgetConfig("financial_weather", 0),
            DashboardWidgetConfig("safe_to_spend", 1),
            DashboardWidgetConfig("spending_pace", 2),
            DashboardWidgetConfig("review_alert", 3),
            DashboardWidgetConfig("spending_trend", 4),
            DashboardWidgetConfig("insight", 5),
            DashboardWidgetConfig("period_summary", 6),
            DashboardWidgetConfig("budget_health", 7),
            DashboardWidgetConfig("top_categories", 8),
            DashboardWidgetConfig("recent_transactions", 9),
            DashboardWidgetConfig("budget_block_party", 10)
        )
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\FinancialWeatherRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositoryfinancialweatherrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.PaceStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.logic.RecurringExpenseEngine
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.SavingsGoalDao
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.PlannedExpensePriority as EntityPlannedPriority
import com.yourname.expensetracker.data.database.entity.GoalProtectionLevel as EntityGoalProtection
import com.yourname.expensetracker.domain.model.*
import com.yourname.expensetracker.domain.model.PlannedExpensePriority as DomainPlannedPriority
import com.yourname.expensetracker.domain.model.GoalProtectionLevel as DomainGoalProtection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Calendar

enum class WeatherState {
    CLEAR_SKIES,      // 🌤️ Comfortable buffer
    PARTLY_CLOUDY,    // ⛅ Moderate, watch spending
    CLOUDY,           // ☁️ Tight, limited discretionary
    RAINY,            // 🌧️ Multiple bills, over pace
    STORMY,           // ⛈️ Budget danger, immediate action
    UNKNOWN
}

data class FinancialWeather(
    val state: WeatherState,
    val headline: String,
    val summary: String,
    val icon: String, // Emoji
    val riskLevel: Int, // 0-100
    val totalCommitted: Double,
    val totalLikely: Double,
    val predictedDiscretionary: Double,
    val discretionaryBudget: Double,
    val pastSpendingPoints: List<Double> = emptyList(),
    val projectedSpendingPoints: List<Double> = emptyList(),
    val upcomingItems: List<UpcomingItem> = emptyList(),
    val totalRecurringCount: Int = 0,
    val details: List<NarrativeSection> = emptyList()
)

@Singleton
class FinancialWeatherRepository @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val insightsEngine: InsightsEngine,
    private val budgetRepository: BudgetRepository,
    private val recurringExpenseEngine: RecurringExpenseEngine,
    private val recurringExpenseDao: com.yourname.expensetracker.data.database.dao.RecurringExpenseDao,
    private val plannedExpenseDao: PlannedExpenseDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val synthesisEngine: SynthesisEngine,
    private val narrativeGenerator: NarrativeGenerator,
    private val analyticsRepository: AnalyticsRepository
) {
    private val calendar = Calendar.getInstance()

    private fun com.yourname.expensetracker.data.database.entity.PlannedExpense.toDomain(): PlannedExpense {
        return PlannedExpense(
            id = this.id,
            description = this.description,
            amount = this.amount,
            date = this.date,
            categoryId = this.categoryId,
            isRecurring = this.isRecurring,
            priority = when(this.priority) {
                EntityPlannedPriority.MUST -> DomainPlannedPriority.MUST
                EntityPlannedPriority.LIKELY -> DomainPlannedPriority.LIKELY
                EntityPlannedPriority.OPTIONAL -> DomainPlannedPriority.OPTIONAL
            }
        )
    }

    fun getFinancialWeather(): Flow<FinancialWeather> = combine(
        notificationRepository.getAllExpenses(),
        budgetRepository.getBudgetStatuses(),
        getAllRecurringPatterns(),
        plannedExpenseDao.getAllPlannedExpenses(),
        savingsGoalDao.getAllGoals()
    ) { expenses, budgetStatuses, recurringPatterns, plannedEntities, goalEntities ->

        val plannedExpenses = plannedEntities.map { it.toDomain() }

        val savingsGoals = goalEntities.map { entity ->
            SavingsGoal(
                id = entity.id,
                name = entity.name,
                targetAmount = entity.targetAmount,
                currentAmount = entity.currentAmount,
                targetDate = entity.targetDate,
                protectionLevel = when(entity.protectionLevel) {
                    EntityGoalProtection.STRICT -> DomainGoalProtection.STRICT
                    EntityGoalProtection.WARNING -> DomainGoalProtection.WARNING
                    EntityGoalProtection.TRACKING -> DomainGoalProtection.TRACKING
                }
            )
        }

        // 1. Calculate Past Daily Cumulative Spend
        // 1. Calculate Past Daily Cumulative Spend
        val now = System.currentTimeMillis()
        val monthStart = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfMonth(now)
        val currentDay = ((now - monthStart) / 86400000L).toInt()

        val purchases = expenses.filter { 
            it.transactionType == TransactionType.PURCHASE && it.date >= monthStart
        }

        val amountByDay = DoubleArray(currentDay + 1)
        val startOfDay = monthStart 

        purchases.forEach { expense ->
             val dayIndex = ((expense.date - startOfDay) / 86400000L).toInt()
             if (dayIndex in 0..currentDay) {
                 amountByDay[dayIndex] += expense.amount
             }
        }

        var runningTotal = 0.0
        val pastSumDaily = (1..currentDay).map { day ->
            runningTotal += amountByDay[day]
            runningTotal
        }

        // 2. Get Engines data - Reusing already fetched expenses to avoid redundant DB queries
        val pace = insightsEngine.getSpendingPaceSuspend(expenses)

        // 3. Synthesize Forecast
        val forecast = synthesisEngine.synthesize(
            pastSumDaily = pastSumDaily,
            recurringPatterns = recurringPatterns,
            plannedExpenses = plannedExpenses,
            savingsGoals = savingsGoals,
            budgetStatuses = budgetStatuses,
            spendingPace = pace
        )

        // 4. Generate Narrative
        val narrative = narrativeGenerator.generate(forecast, budgetStatuses)

        // 5. Map to UI Model
        FinancialWeather(
            state = narrative.state,
            headline = narrative.headline,
            summary = narrative.summary,
            icon = narrative.icon,
            riskLevel = when (forecast.components.riskLevel) {
                RiskLevel.LOW -> 10
                RiskLevel.MEDIUM -> 40
                RiskLevel.HIGH -> 70
                RiskLevel.CRITICAL -> 100
            },
            totalCommitted = forecast.components.totalCommitted,
            totalLikely = forecast.components.totalLikely,
            predictedDiscretionary = forecast.components.predictedDiscretionary,
            discretionaryBudget = forecast.components.discretionaryBudget,
            pastSpendingPoints = forecast.components.pastSpendingPoints,
            projectedSpendingPoints = forecast.components.projectedSpendingPoints,
            upcomingItems = buildUpcomingItems(
                forecast.components.recurringExpenses,
                forecast.components.plannedExpenses
            ),
            totalRecurringCount = recurringPatterns.size,
            details = narrative.details
        )
    }.catch { e ->
        android.util.Log.e("FinancialWeatherRepo", "Error generating weather", e)
        emit(FinancialWeather(
            state = WeatherState.UNKNOWN,
            headline = "Weather Unavailable",
            summary = "We couldn't calculate your financial outlook right now.",
            icon = "❓",
            riskLevel = 0,
            totalCommitted = 0.0,
            totalLikely = 0.0,
            predictedDiscretionary = 0.0,
            discretionaryBudget = 0.0
        ))
    }

    private fun buildUpcomingItems(
        recurring: List<RecurringPattern>,
        planned: List<PlannedExpense>
    ): List<UpcomingItem> {
        val now = System.currentTimeMillis()
        val startOfToday = com.yourname.expensetracker.domain.util.TimePeriodUtils.getStartOfDay(now)
        val horizon = startOfToday + (31 * 86_400_000L) // Show next 31 days

        val items = mutableListOf<com.yourname.expensetracker.domain.model.UpcomingItem>()

        recurring.filter { it.nextExpectedDate in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Recurring(it)) }

        planned.filter { it.date in startOfToday..horizon }
            .forEach { items.add(UpcomingItem.Planned(it)) }

        return items.sortedBy { it.date }
    }

    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> = plannedExpenseDao.getAllPlannedExpenses()
        .map { entities -> entities.map { it.toDomain() } }

    fun getAllRecurringPatterns(): Flow<List<RecurringPattern>> = recurringExpenseDao.getAllFlow()
        .map { recurringExpenseEngine.getPatterns() }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\MerchantCategoryRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositorymerchantcategoryrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantCategoryRepository @Inject constructor(
    private val dao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {

    /**
     * Learns a merchant -> category mapping.
     * Normalizes the merchant name before saving.
     */
    suspend fun learnPattern(merchantName: String, categoryId: Long) {
        val pattern = categorizationEngine.normalize(merchantName)
        if (pattern.isNotEmpty()) {
            dao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = categoryId,
                    confidence = 1.0f
                )
            )
        }
    }

    suspend fun getCategoryForMerchant(merchantName: String): MerchantCategory? {
        val pattern = categorizationEngine.normalize(merchantName)
        return dao.getCategoryForMerchant(pattern)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.intelligence.ml.MatchType
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.data.database.model.PendingReviewWithReceipt
import com.yourname.expensetracker.domain.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val scannedReceiptDao: ScannedReceiptDao,

    private val parserRegistry: AppParserRegistry,
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor 
) {
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    // Shared expenses flow to prevent redundant DB queries (shared by multiple ViewModels)
    private val sharedExpenses = expenseDao.getAllFlow()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(30000),
            replay = 1
        )

    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean =
        dao.exists(packageName, timestamp, title, text)

    // === Review Queue ===
    fun getPendingReviews(limit: Int = 100): Flow<List<PendingReviewWithReceipt>> = pendingReviewDao.getPendingFlow(limit)
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Classifier Stats ===
    fun getClassifierStatsFlow(): Flow<ClassifierStats> = classifier.stats
    suspend fun getClassifierStats() = classifier.getStats()

    // === Manual Expense Entry ===

    /**
     * Search merchants from existing expenses for autocomplete
     */
    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList()
        return expenseDao.searchMerchants(query)
    }

    /**
     * Get recent distinct merchant names for suggestions
     */
    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }

    /**
     * Add a manually entered expense
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        transactionType: TransactionType = TransactionType.PURCHASE,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: Long = System.currentTimeMillis(),
        notes: String? = null
    ): OperationResult<Long> {
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Manual expense amount too large: $amount")
            return OperationResult.Error("Amount exceeds limit")
        }

        // 1. Normalize merchant name
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }

                // 3. Dedup check with tighter window for manual entries (1 minute)
                // For manual entries, we trust the user but want to avoid accidental double-taps.
                val isDuplicate = expenseDao.isDuplicate(
                    amount = amount,
                    merchant = normalizedMerchant,
                    date = date,
                    windowMs = 60000 // 1 minute window for manual double-entry prevention
                )
        if (isDuplicate) return OperationResult.Duplicate

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = transactionType,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true,
            notes = notes
        )

        val id = expenseDao.insert(expense)

        // 5. Check budgets
        budgetMonitor.checkBudgets()

        // 6. Learn the merchant→category mapping for future auto-categorization
        if (finalCategoryId != null && id > 0) {
            merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
        }

        return OperationResult.Success(id)
    }

    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }

    // === Analytics Helpers ===

    suspend fun getExpenseCountForPeriod(startMs: Long, endMs: Long): Int =
        expenseDao.getCountForPeriod(startMs, endMs)

    // === Core Processing Pipeline ===

    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        // 1. Initial existence check (fast, non-transactional)
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return

        // 2. Heavy CPU/IO Work - MOVE OUTSIDE TRANSACTION
        // Initialize classifier if needed
        classifier.initialize()

        // Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            database.withTransaction {
                // Secondary check inside transaction to prevent race conditions
                if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction

                val rawId = try { dao.insert(notification) } catch (e: Exception) { return@withTransaction }
                sourceStatsDao.incrementTotal(notification.packageName)
                sourceStatsDao.incrementAutoRejected(notification.packageName)
                dao.markRelevance(rawId, false)
            }
            return
        }

        // Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        // Route through confidence system (includes source stats + ML)
        var routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )

        // Fix 4.12: Large amount validation -> Force Needs Review
        if (parsed.amount > 1000000.0 && routingResult.decision == RoutingDecision.AUTO_ACCEPT) {
            android.util.Log.w("NotificationRepo", "Auto-accept suppressed due to large amount: ${parsed.amount}")
            routingResult = routingResult.copy(decision = RoutingDecision.NEEDS_REVIEW)
        }

        // Apply merchant normalization & user corrections
        val lookupResult = merchantNormalizer.normalize(parsed.merchant)
        val correctedMerchant = lookupResult.canonical.normalizedName

        // 3. Database Transaction - ONLY MINIMAL DB WRITES
        database.withTransaction {
            // Secondary check inside transaction
            if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return@withTransaction

            // Save raw notification
            val rawId = try {
                dao.insert(notification)
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                return@withTransaction
            }

            // Update stats
            confidenceRouter.ensureSourceStats(notification.packageName)
            sourceStatsDao.incrementTotal(notification.packageName)

            when (routingResult.decision) {
                RoutingDecision.AUTO_ACCEPT -> {
                    // Check for duplicates
                    val isDuplicate = expenseDao.isDuplicate(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000 
                    )

                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementDuplicate(notification.packageName)

                        // Train ML classifier: duplicates are still valid transactions
                        classifier.train(fullNotificationText, isTransaction = true)

                        return@withTransaction
                    }

                    val classification = hybridClassifier.classify(
                        merchantName = correctedMerchant,
                        amount = parsed.amount,
                        notificationTitle = notification.title,
                        notificationText = notification.text,
                        packageName = notification.packageName
                    )
                    val categoryId = classification.categoryId.takeIf { it > 0 }

                    val expense = Expense(
                        amount = parsed.amount,
                        currency = parsed.currency,
                        merchant = correctedMerchant,
                        transactionType = parsed.type,
                        date = notification.timestamp,
                        rawNotificationId = rawId,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.CARD,
                        isManualEntry = false
                    )
                    try {
                        expenseDao.insert(expense)
                        dao.markRelevance(rawId, true)
                        sourceStatsDao.incrementAccepted(notification.packageName)

                        // Check budgets (Note: potentially heavy, but standard for accept flow)
                        budgetMonitor.checkBudgets()

                        // Train classifier: auto-accepted = positive example
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: android.database.sqlite.SQLiteConstraintException) {
                        dao.markRelevance(rawId, false)
                    }
                }

                RoutingDecision.NEEDS_REVIEW -> {
                    // Check for duplicates before adding to review
                    val isDuplicate = expenseDao.isDuplicate(
                        amount = parsed.amount,
                        merchant = correctedMerchant,
                        date = notification.timestamp,
                        windowMs = 300000
                    )
                    if (isDuplicate) {
                        dao.markRelevance(rawId, false)
                        sourceStatsDao.incrementDuplicate(notification.packageName)

                        // Train ML classifier: duplicates are still valid transactions
                        classifier.train(fullNotificationText, isTransaction = true)

                        return@withTransaction
                    }

                    val classification = hybridClassifier.classify(
                        merchantName = correctedMerchant,
                        amount = parsed.amount,
                        notificationTitle = notification.title,
                        notificationText = notification.text,
                        packageName = notification.packageName
                    )
                    val suggestedCategoryId = classification.categoryId.takeIf { it > 0 }

                    val review = PendingReview(
                        rawNotificationId = rawId,
                        suggestedAmount = parsed.amount,
                        suggestedCurrency = parsed.currency,
                        suggestedMerchant = correctedMerchant,
                        suggestedType = parsed.type.name,
                        suggestedCategoryId = suggestedCategoryId,
                        confidence = routingResult.adjustedConfidence,
                        packageName = notification.packageName,
                        notificationTitle = notification.title,
                        notificationText = notification.text ?: notification.bigText,
                        suggestedDate = parsed.date
                    )
                    pendingReviewDao.insert(review)
                    sourceStatsDao.incrementPending(notification.packageName)
                }

                RoutingDecision.AUTO_REJECT -> {
                    dao.markRelevance(rawId, false)
                    sourceStatsDao.incrementAutoRejected(notification.packageName)
                }
            }
        }
    }

    // === Review Actions ===

    /**
     * User approves a pending review (possibly with modifications)
     */
    /**
     * User approves a pending review (possibly with modifications)
     */
    @Transaction
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ): OperationResult<Long> {
        val review = pendingReviewDao.getById(reviewId) ?: return OperationResult.Error("Review not found")

        // Critical Fix: Atomically check and update status to prevent double-processing
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "PROCESSING")
        if (rowsUpdated == 0) return OperationResult.Error("Review already processed")

        // If we fail later, we should ideally revert this, but for now we secure the lock.
        // We will update to APPROVED at the end.

        val amount: Double = finalAmount ?: review.suggestedAmount
        val merchant: String = finalMerchant ?: review.suggestedMerchant
        val categoryId: Long? = finalCategoryId ?: review.suggestedCategoryId
        // Fix 4.12: Large amount validation
        if (amount > 1000000.0) {
            android.util.Log.w("NotificationRepo", "Approval suppressed due to large amount: $amount")
            pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
            return OperationResult.Error("Amount exceeds limit")
        }

        val type: com.yourname.expensetracker.data.database.entity.TransactionType = try {
            com.yourname.expensetracker.data.database.entity.TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            android.util.Log.w("NotificationRepo", "Unknown transaction type: ${review.suggestedType}, falling back to PURCHASE")
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE
        }

        val notification = review.rawNotificationId?.let { dao.getById(it) }
        val transactionDate: Long = review.suggestedDate ?: notification?.timestamp ?: review.createdAt

        // Check for duplicates
        // Increased window to 5 minutes to catch delayed bank notifications
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate,
            windowMs = 300000
        )

        if (!isDuplicate) {
            // Create the expense
            val expense = com.yourname.expensetracker.data.database.entity.Expense(
                0L,
                amount,
                review.suggestedCurrency,
                merchant,
                type,
                transactionDate,
                review.rawNotificationId,
                categoryId,
                System.currentTimeMillis(),
                com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD,
                review.scannedReceiptId != null,
                if (review.scannedReceiptId != null) "Scanned from receipt" else null
            )

            try {
                val expenseId = expenseDao.insert(expense)

                if (expenseId > 0) {
                    review.rawNotificationId?.let { dao.markRelevance(it, true) }
                    sourceStatsDao.incrementAccepted(review.packageName)
                    sourceStatsDao.decrementPending(review.packageName)

                    // Link to scanned receipt if this was a scan
                    review.scannedReceiptId?.let { receiptId ->
                        scannedReceiptDao.linkToExpense(receiptId, expenseId)
                    }

                    // Check budgets
                    budgetMonitor.checkBudgets()

                    // Fix 1.19 status update: We update it to APPROVED
                    pendingReviewDao.updateStatus(reviewId, "APPROVED")

                    // Record user correction for learning
                    val correction = UserCorrection(
                        packageName = review.packageName,
                        originalMerchant = review.suggestedMerchant,
                        correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                            finalMerchant else null,
                        originalAmount = review.suggestedAmount,
                        correctedAmount = if (finalAmount != null && finalAmount != review.suggestedAmount)
                            finalAmount else null,
                        originalCategoryId = review.suggestedCategoryId,
                        correctedCategoryId = if (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)
                            finalCategoryId else null,
                        wasRejected = false,
                        wasApproved = true,
                        notificationTitle = review.notificationTitle,
                        notificationText = review.notificationText
                    )
                    userCorrectionDao.insert(correction)

                    // Retrain classifier
                    try {
                        classifier.retrainFromCorrections()
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
                    }

                    // Learn mapping
                    if (categoryId != null) {
                        merchantCategoryRepository.learnPattern(merchant, categoryId)
                    }

                    // Learn alias if merchant name was changed (BUG-MERC-001)
                    if (finalMerchant != null && finalMerchant != review.suggestedMerchant) {
                        merchantNormalizer.learnMerchantAlias(review.suggestedMerchant, finalMerchant)
                    }

                    return OperationResult.Success(expenseId)
                } else {
                    pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
                    return OperationResult.Error("Insertion failed")
                }
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // Unexpected constraint error, fail the operation
                pendingReviewDao.updateStatus(reviewId, "PENDING") // Revert status
                return OperationResult.Error("Database constraint error: ${e.message}")
            }
        } else {
             // It's a duplicate, we treat this as "processed" to clear the review
             sourceStatsDao.incrementDuplicate(review.packageName)
             sourceStatsDao.decrementPending(review.packageName)
             pendingReviewDao.updateStatus(reviewId, "DUPLICATE")

             // Train classifier: user approved this as an expense (even if duplicate)
             val fullText = listOfNotNull(
                 review.notificationTitle,
                 review.notificationText
             ).joinToString(" ")
             classifier.train(fullText, isTransaction = true)

             return OperationResult.Duplicate
        }
    }

    /**
     * User rejects a pending review
     */
    @Transaction
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        // Atomic update check
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED")
        if (rowsUpdated == 0) return

        review.rawNotificationId?.let { id -> dao.markRelevance(id, false) }
        sourceStatsDao.incrementRejected(review.packageName)
        sourceStatsDao.decrementPending(review.packageName)

        // Record rejection for learning
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = null,
            originalAmount = review.suggestedAmount,
            correctedAmount = null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = null,
            wasRejected = true,
            wasApproved = false,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)

        // Train classifier: user rejected = negative
        // LOG-003 Fix: Use retrainFrom corrections
        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }

    /**
     * Approves all currently pending reviews
     */
    @Transaction
    suspend fun approveAllReview() {
        val pending = pendingReviewDao.getPending()
        pending.forEach { item ->
            approveReview(item.review.id)
        }
    }

    // === Classifier Management ===

    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }

    // === Existing methods (updated) ===

    @Transaction
    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) {
        val notification = dao.getById(id) ?: return
        dao.markRelevance(id, isRelevant)

        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")

        if (isRelevant) {
            // CRITICAL FIX: When user explicitly marks as relevant (Expense ✓),
            // we must actually CREATE the expense or review item.

            // 1. Try to parse again
            val parsed = parserRegistry.parse(
                title = notification.title,
                text = notification.text,
                bigText = notification.bigText,
                subText = notification.subText,
                packageName = notification.packageName
            )

            if (parsed != null) {
                // We have valid data, so we can create an Expense directly (User Override)
                // We assume if they clicked "Expense", they validated it looks correct-ish,
                // or at least we should create it so they can see it.

                // Normalization
                val lookupResult = merchantNormalizer.normalize(parsed.merchant)
                val correctedMerchant = lookupResult.canonical.normalizedName

                // Categorization
                val classification = hybridClassifier.classify(
                    merchantName = correctedMerchant,
                    amount = parsed.amount,
                    notificationTitle = notification.title,
                    notificationText = notification.text,
                    packageName = notification.packageName
                )
                val categoryId = classification.categoryId.takeIf { it > 0 }

                // Check for duplicates before inserting
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp,
                    windowMs = 300000
                )

                if (isDuplicate) {
                    sourceStatsDao.incrementDuplicate(notification.packageName)

                    // Train classifier: user manually marked this as an expense
                    classifier.train(fullNotificationText, isTransaction = true)
                } else {
                    val expense = Expense(
                        amount = parsed.amount,
                        currency = parsed.currency,
                        merchant = correctedMerchant,
                        transactionType = parsed.type,
                        date = notification.timestamp,
                        rawNotificationId = id,
                        categoryId = categoryId,
                        paymentMethod = PaymentMethod.CARD,
                        isManualEntry = false,
                        notes = "Manually recovered from debug log"
                    )

                    try {
                        expenseDao.insert(expense)
                        sourceStatsDao.incrementAccepted(notification.packageName)
                        // Decrease auto-rejected count since we reversed the decision
                        // (Optional, but keeps stats cleaner)

                        budgetMonitor.checkBudgets()

                        // Train classifier
                        classifier.train(fullNotificationText, isTransaction = true)
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationRepo", "Failed to insert recovered expense", e)
                    }
                }
            } else {
                // Parsing failed, but user says it's an expense.
                // Create a PendingReview with blank values so they can fill it in.
                val review = PendingReview(
                    rawNotificationId = id,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Unknown",
                    suggestedType = TransactionType.PURCHASE.name,
                    suggestedCategoryId = null,
                    confidence = 1.0f, // Manual override = 100% confidence
                    packageName = notification.packageName,
                    notificationTitle = notification.title,
                    notificationText = notification.text ?: notification.bigText,
                    suggestedDate = notification.timestamp
                )
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }
        }

        // Train classifier directly from this manual action
        // Also record a correction for future retraining (LOG-003)
        // We record correction AND retrain immediately to ensure consistency
        val correction = UserCorrection(
            packageName = notification.packageName,
            originalMerchant = "Manual",
            correctedMerchant = null,
            originalAmount = 0.0,
            correctedAmount = null,
            originalCategoryId = null,
            correctedCategoryId = null,
            wasRejected = !isRelevant,
            wasApproved = isRelevant,
            notificationTitle = notification.title,
            notificationText = notification.text ?: notification.bigText
        )
        userCorrectionDao.insert(correction)

        try {
            classifier.retrainFromCorrections()
        } catch (e: Exception) {
            android.util.Log.e("NotificationRepo", "Failed to retrain classifier", e)
        }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        // merchantCategoryDao.deleteAll() // Removed as part of refactoring
        sourceStatsDao.resetAllPendingCounts()
    }

    suspend fun resetSourceStats() {
        sourceStatsDao.deleteAll()
    }

    suspend fun deleteAllExpenses() = expenseDao.deleteAll()

    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)

    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        merchantCategoryRepository.learnPattern(expense.merchant, newCategoryId)

        // Also record as a correction for learning
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }

    suspend fun updateExpenseMerchant(expense: Expense, newMerchant: String) {
        if (expense.merchant == newMerchant) return

        expenseDao.updateMerchant(expense.id, newMerchant)

        // Catch the rename for future auto-correction (BUG-MERC-001)
        // We link whatever the current normalized merchant name is to the new brand name
        merchantNormalizer.learnMerchantAlias(expense.merchant, newMerchant)

        // Also learn the category for this brand name
        expense.categoryId?.let { 
            merchantCategoryRepository.learnPattern(newMerchant, it)
        }
    }

    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == "PENDING") {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        pendingReviewDao.deleteByRawId(notification.id) // Fix 1.20: Clean up orphaned reviews
        dao.delete(notification)
    }

    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))

    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)

    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)

    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()

    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()

    fun getAllExpenses(): Flow<List<Expense>> = sharedExpenses

    fun getExpensesWithCategory(limit: Int = 200): Flow<List<ExpenseWithCategory>> =
        expenseDao.getAllWithCategoryFlow(limit)

    fun getExpensesWithCategoryInPeriod(startMs: Long, endMs: Long): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryInPeriodFlow(startMs, endMs)

    fun getExpensesWithCategoryFiltered(
        startMs: Long, 
        endMs: Long, 
        type: TransactionType?,
        categoryId: Long?, 
        merchant: String?
    ): Flow<List<ExpenseWithCategory>> =
        expenseDao.getExpensesWithCategoryFilteredFlow(
            startMs = startMs,
            endMs = endMs,
            type = type?.name,
            categoryId = categoryId,
            merchant = merchant
        )

    suspend fun getExpensesPaged(limit: Int, offset: Int): List<ExpenseWithCategory> =
        expenseDao.getExpensesWithCategoryPaged(limit, offset)

    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        if (notifications.isEmpty()) return

        // Initialize once for the batch
        classifier.initialize()

        // Process in parallel chunks
        notifications.chunked(20).forEach { chunk ->
            coroutineScope {
                chunk.map { notification -> 
                    async { processAndSave(notification) } 
                }.awaitAll()
            }
        }
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\PlannedExpenseRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositoryplannedexpenserepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlannedExpenseRepository @Inject constructor(
    private val plannedExpenseDao: PlannedExpenseDao
) {
    fun getAllPlannedExpenses(): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getAllPlannedExpenses()
    }

    fun getPlannedExpensesForPeriod(startMs: Long, endMs: Long): Flow<List<PlannedExpense>> {
        return plannedExpenseDao.getPlannedExpensesForPeriod(startMs, endMs)
    }

    suspend fun addPlannedExpense(expense: PlannedExpense): Long {
        return plannedExpenseDao.insertPlannedExpense(expense)
    }

    suspend fun deletePlannedExpense(expense: PlannedExpense) {
        plannedExpenseDao.deletePlannedExpense(expense)
    }

    suspend fun deletePlannedExpenseById(id: Long) {
        plannedExpenseDao.deletePlannedExpenseById(id)
    }
}

```

---

## app\src\main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt <a name="appsrcmainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository

import android.net.Uri
import java.util.Date
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer as NewMerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.ml.HybridExpenseClassifier
import com.yourname.expensetracker.domain.receipt.BankStatementParser
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
// import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryRepository: MerchantCategoryRepository, // <-- Replaces DAO
    private val pendingReviewDao: PendingReviewDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val statementParser: BankStatementParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: NewMerchantNormalizer,
    private val hybridClassifier: HybridExpenseClassifier,
    private val budgetMonitor: BudgetMonitor
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     *
     * @param imageUri URI of the image to process
     * @param autoCreateReview Whether to automatically create a PendingReview entry (true for batch, false for manual)
     */
    suspend fun processReceipt(
        imageUri: Uri,
        autoCreateReview: Boolean = false
    ): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Run OCR (Separate Try-Catch to distinguish OCR failure vs Parse failure)
        val ocrResult = try {
            ocrService.processUri(imageUri)
        } catch (e: Exception) {
            android.util.Log.e("ReceiptRepository", "OCR Failed for $imageUri", e)
            // Fallback: Try to save the image using manual record logic
            return saveManualReceiptRecord(imageUri).let { (receipt, parsed) ->
                val failedReceipt = receipt.copy(
                    rawOcrText = "Scan Failed: ${e.message}", 
                    confidence = com.yourname.expensetracker.domain.util.AppConstants.Confidence.RECEIPT_FALLBACK
                )
                scannedReceiptDao.update(failedReceipt)
                Pair(failedReceipt, parsed)
            }
        }

        try {
            // 2. Parse the OCR text
            val parsed = receiptParser.parse(ocrResult.fullText)

            // 3. Normalize merchant if found
            val lookupResult = parsed.merchantName?.let {
                merchantNormalizer.normalize(it, autoCreate = true)
            }
            val normalizedMerchant = lookupResult?.canonical?.normalizedName

            // 4. Save scanned receipt record
            val receipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText,
                parsedTotal = parsed.total,
                parsedMerchant = normalizedMerchant ?: parsed.merchantName,
                parsedDate = parsed.date,
                parsedItems = if (parsed.lineItems.isNotEmpty())
                    receiptParser.lineItemsToJson(parsed.lineItems) else null,
                parsedTaxAmount = parsed.tax,
                currency = parsed.currency,
                confidence = parsed.confidence
            )

            val receiptId = scannedReceiptDao.insert(receipt)

            // 5. Optionally create a PendingReview (True for Batch, False for FAB Manual Scan)
            if (autoCreateReview) {
                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = parsed.total ?: 0.0,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = normalizedMerchant ?: parsed.merchantName ?: "Unknown Merchant",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedDate = parsed.date, // Preserving the date found by parser
                    confidence = parsed.confidence,
                    packageName = "receipt.scan",
                    notificationTitle = "Scanned Receipt",
                    notificationText = ocrResult.fullText.take(200), // Preview snippet
                    suggestedCategoryId = normalizedMerchant?.let { 
                         hybridClassifier.classify(it, parsed.total ?: 0.0).categoryId.takeIf { id -> id > 0 }
                    }
                )
                pendingReviewDao.insert(review)
            }
            return Pair(receipt.copy(id = receiptId), parsed)

        } catch (e: Exception) {
            // Parsing Logic Failed, but we HAVE the OCR text!
            // Save it so user can manually edit without losing the text.
            android.util.Log.e("ReceiptRepository", "Parsing Failed for $imageUri", e)

            val failedReceipt = ScannedReceipt(
                imagePath = ocrResult.savedImagePath,
                rawOcrText = ocrResult.fullText, // PRESERVED!
                parsedTotal = null,
                parsedMerchant = null,
                parsedDate = null, 
                parsedItems = null,
                parsedTaxAmount = null, // Explicitly null for failed parse
                currency = "EUR",
                confidence = 0f
            )
            val receiptId = scannedReceiptDao.insert(failedReceipt)

            if (autoCreateReview) {
                 val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = 0.0,
                    suggestedCurrency = "EUR",
                    suggestedMerchant = "Parsing Failed",
                    suggestedType = com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE.name,
                    suggestedCategoryId = null, // No category for failed parse
                    confidence = 0f,
                    packageName = "receipt.scan.error",
                    notificationTitle = "Parsing Failed",
                    notificationText = "OCR Text preserved. Manual entry required."
                )
                pendingReviewDao.insert(review)
            }

            return Pair(failedReceipt.copy(id = receiptId), ReceiptParser.ParsedReceipt(null, null, null, null, System.currentTimeMillis(), "EUR", emptyList(), 0f))
        }
    }

    suspend fun saveManualReceiptRecord(imageUri: android.net.Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Try to at least copy the image for display if possible, or use original
        // For simplicity, we'll try to get ocrService to at least give us a path if it can load the bitmap
        val path = try {
            // We'll reuse the OCR service's image saving logic if possible
            // But if it fails, we fall back to the original URI string (not ideal but better than nothing)
            ocrService.processImage(imageUri).savedImagePath
        } catch (e: Exception) {
            imageUri.toString()
        }

        val receipt = ScannedReceipt(
            imagePath = path,
            rawOcrText = "[OCR Failed or Skipped]",
            parsedTotal = null,
            parsedMerchant = null,
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = "EUR",
            confidence = 0f
        )
        val receiptId = scannedReceiptDao.insert(receipt)

        return Pair(
            receipt.copy(id = receiptId),
            ReceiptParser.ParsedReceipt(
                merchantName = null,
                total = null,
                subtotal = null,
                tax = null,
                date = System.currentTimeMillis(),
                currency = "EUR",
                lineItems = emptyList(),
                confidence = 0f
            )
        )
    }

    /**
     * Create an expense from a scanned receipt (after user review/edit)
     */
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): com.yourname.expensetracker.domain.model.OperationResult<Long> {
        // 1. Normalize merchant
        val lookupResult = merchantNormalizer.normalize(merchant, autoCreate = true)
        val normalizedMerchant = lookupResult.canonical.normalizedName

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: hybridClassifier.classify(
            merchantName = normalizedMerchant,
            amount = amount
        ).categoryId.takeIf { it > 0 }

        // 3. Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = com.yourname.expensetracker.domain.util.AppConstants.Windows.DUPLICATE_DETECTION
        )
        if (isDuplicate) return com.yourname.expensetracker.domain.model.OperationResult.Duplicate

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true, // Scanned receipts are treated as manual entries
            notes = notes ?: "Scanned from receipt"
        )

        val expenseId = expenseDao.insert(expense)

        // 5. Link receipt to expense
        if (expenseId > 0) {
            scannedReceiptDao.linkToExpense(receiptId, expenseId)

            // 6. Check budgets
            budgetMonitor.checkBudgets()

            // 7. Learn merchant → category mapping
            if (finalCategoryId != null) {
                try {
                    hybridClassifier.learnFromCorrection(
                        merchantName = normalizedMerchant,
                        correctCategoryId = finalCategoryId,
                        amount = amount
                    )
                } catch (e: Exception) {
                    android.util.Log.e("ReceiptRepo", "Failed to learn categorization", e)
                }
                merchantCategoryRepository.learnPattern(normalizedMerchant, finalCategoryId)
            }
        }

        return com.yourname.expensetracker.domain.model.OperationResult.Success(expenseId)
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        ocrService.deleteImage(receipt.imagePath)
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }

    data class BatchResult(
        val successCount: Int,
        val failureCount: Int,
        val errors: List<String>,
        val debugData: com.yourname.expensetracker.ui.screens.debug.DebugData? = null
    )

    /**
     * Process multiple receipts in parallel with a concurrency limit to prevent OOM.
     */
    suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult = coroutineScope {
        // Deduplicate URIs to avoid processing the same file twice
        val uniqueUris = uris.distinctBy { it.toString() }
        if (uniqueUris.size < uris.size) {
            android.util.Log.d("ReceiptRepository", "Removed ${uris.size - uniqueUris.size} duplicate URIs")
        }

        val semaphore = Semaphore(3) // Limit to 3 concurrent OCR tasks
        val total = uniqueUris.size
        var successes = 0
        var failures = 0
        val errors = mutableListOf<String>()
        val mutex = Mutex()

        val jobs = uniqueUris.map { uri ->
            async {
                try {
                    semaphore.withPermit {
                        processReceipt(uri, autoCreateReview = true)
                    }
                    mutex.withLock {
                        successes++
                        onProgress(successes + failures, total)
                    }
                } catch (e: Exception) {
                    mutex.withLock {
                        failures++
                        errors.add("Failed to process $uri: ${e.message}")
                        onProgress(successes + failures, total)
                    }
                }
            }
        }

        jobs.awaitAll()
        BatchResult(successes, failures, errors)
    }

    /**
     * Process an image URI as a bank statement: extracting multiple transactions
     */
    suspend fun processStatement(imageUri: Uri): BatchResult {
        val startTime = System.currentTimeMillis()
        val parsingLogs = mutableListOf<String>()

        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processUri(imageUri)

        // 2. Parse as multiple transactions using spatial data
        val parsedTransactions = statementParser.parse(ocrResult.blocks)

        if (parsedTransactions.isEmpty()) {
            parsingLogs.add("No transactions found in bank statement")
            val debugData = com.yourname.expensetracker.ui.screens.debug.DebugData(
                rawText = ocrResult.fullText,
                parsedTransactions = emptyList(),
                parsingLogs = parsingLogs,
                processingTimeMs = System.currentTimeMillis() - startTime,
                parserUsed = "BankStatementParser"
            )
            return BatchResult(0, 1, listOf("No transactions found in screenshot"), debugData)
        }

        // 3. Save common scanned receipt record
        val receiptRecord = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = null, // Varies per transaction
            parsedMerchant = "Bank Statement",
            parsedDate = System.currentTimeMillis(),
            parsedItems = null,
            parsedTaxAmount = null,
            currency = parsedTransactions.firstOrNull()?.currency ?: "EUR",
            confidence = 0.8f
        )
        val receiptId = scannedReceiptDao.insert(receiptRecord)

        // 4. Create a PendingReview for EACH transaction found
        var successCount = 0
        val errors = mutableListOf<String>()

        parsedTransactions.forEach { tx ->
            try {
                // Normalize merchant
                val lookupResult = merchantNormalizer.normalize(tx.merchant, autoCreate = true)
                val normalizedMerchant = lookupResult.canonical.normalizedName

                val classification = hybridClassifier.classify(
                    merchantName = normalizedMerchant,
                    amount = tx.amount
                )

                val review = PendingReview(
                    rawNotificationId = null,
                    scannedReceiptId = receiptId,
                    suggestedAmount = tx.amount,
                    suggestedCurrency = tx.currency,
                    suggestedMerchant = normalizedMerchant,
                    suggestedType = tx.type.name,
                    suggestedCategoryId = classification.categoryId.takeIf { id -> id > 0 },
                    suggestedDate = tx.date ?: System.currentTimeMillis(),
                    confidence = tx.confidence,
                    packageName = "statement.import",
                    notificationTitle = "Bank Screenshot",
                    notificationText = "Imported from screenshot: ${tx.merchant}"
                )
                pendingReviewDao.insert(review)
                successCount++
            } catch (e: Exception) {
                val errorMsg = "Failed to save transaction ${tx.merchant}: ${e.message}"
                errors.add(errorMsg)
                parsingLogs.add(errorMsg)
            }
        }

        // Add low confidence warnings to logs
        parsedTransactions.filter { it.confidence < 0.7f }.forEach { tx ->
            parsingLogs.add("Low confidence (${(tx.confidence * 100).toInt()}%) for ${tx.merchant}")
        }

        // Detect issues automatically
        val issues = com.yourname.expensetracker.ui.screens.debug.DebugIssueDetector.detectIssues(
            rawText = ocrResult.fullText,
            transactions = parsedTransactions,
            processingTimeMs = System.currentTimeMillis() - startTime
        )

        // Create debug data
        val debugData = com.yourname.expensetracker.ui.screens.debug.DebugData(
            rawText = ocrResult.fullText,
            parsedTransactions = parsedTransactions,
            parsingLogs = parsingLogs,
            processingTimeMs = System.currentTimeMillis() - startTime,
            parserUsed = "BankStatementParser (${parsedTransactions.size} transactions)",
            issues = issues
        )

        return BatchResult(successCount, parsedTransactions.size - successCount, errors, debugData)
    }

    suspend fun clearAllScannedReceipts() {
        val receipts = scannedReceiptDao.getAll()
        receipts.forEach { ocrService.deleteImage(it.imagePath) }
        scannedReceiptDao.deleteAll()
    }

    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    /**
     * Concatenates all raw OCR text from the database for debugging/parsing refinement
     */
    suspend fun exportParserDebugData(): String {
        val receipts = scannedReceiptDao.getAll()
        val sb = StringBuilder()
        sb.append("=== EXPORTED PARSER DEBUG DATA (${receipts.size} RECEIPTS) ===\n\n")
        receipts.forEachIndexed { index, receipt ->
            sb.append("--- RECEIPT #${index + 1} (ID: ${receipt.id}) ---\n")
            sb.append(formatReceiptDebug(receipt))
            sb.append("\n\n")
        }
        return sb.toString()
    }

    /**
     * Debug function to get detailed info about a scanned receipt
     */
    suspend fun debugReceipt(receiptId: Long): String {
        val receipt = scannedReceiptDao.getById(receiptId) ?: return "Not found"
        return formatReceiptDebug(receipt)
    }

    private fun formatReceiptDebug(receipt: ScannedReceipt): String {
        return """
            ═════════════════════════════════════════
            RECEIPT DEBUG REPORT (ID: ${receipt.id})
            ═════════════════════════════════════════

            IMAGE PATH: ${receipt.imagePath}

            RAW OCR TEXT:
            ┌─────────────────────────────────────┐
            ${receipt.rawOcrText}
            └─────────────────────────────────────┘

            PARSED VALUES:
            • Merchant:  ${receipt.parsedMerchant ?: "NULL"}
            • Total:     ${receipt.parsedTotal ?: "NULL"}
            • Date:      ${receipt.parsedDate?.let { Date(it) } ?: "NULL"}
            • Tax:       ${receipt.parsedTaxAmount ?: "NULL"}
            • Currency:  ${receipt.currency}
            • Confidence: ${receipt.confidence}

            LINE ITEMS:
            ${receipt.parsedItems ?: "None"}

            ═════════════════════════════════════════
        """.trimIndent()
    }
}

```

---

