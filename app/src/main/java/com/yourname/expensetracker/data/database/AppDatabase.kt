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
        SourceStats::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(com.yourname.expensetracker.data.database.converter.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun blockedPackageDao(): BlockedPackageDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun sourceStatsDao(): SourceStatsDao
}
