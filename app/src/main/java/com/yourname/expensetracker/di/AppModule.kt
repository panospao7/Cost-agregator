package com.yourname.expensetracker.di

import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "expense_tracker_db"
        ).addMigrations(
            AppDatabase.MIGRATION_6_7, 
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14
        )
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    android.util.Log.d("AppDatabase", "Database opened successfully. Version: ${db.version}")
                }
            })
            .fallbackToDestructiveMigration()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
    
    @Provides
    @Singleton
    fun providePlannedExpenseDao(database: AppDatabase): PlannedExpenseDao {
        return database.plannedExpenseDao()
    }

    @Provides
    @Singleton
    fun provideSavingsGoalDao(database: AppDatabase): SavingsGoalDao {
        return database.savingsGoalDao()
    }
    
    @Provides
    @Singleton
    fun provideRawNotificationDao(database: AppDatabase): RawNotificationDao {
        return database.rawNotificationDao()
    }

    @Provides
    @Singleton
    fun provideBlockedPackageDao(database: AppDatabase): BlockedPackageDao {
        return database.blockedPackageDao()
    }

    @Provides
    @Singleton
    fun provideExpenseDao(database: AppDatabase): ExpenseDao {
        return database.expenseDao()
    }
    
    @Provides
    @Singleton
    fun provideBudgetDao(database: AppDatabase): BudgetDao {
        return database.budgetDao()
    }

    @Provides
    @Singleton
    fun provideScannedReceiptDao(database: AppDatabase): ScannedReceiptDao {
        return database.scannedReceiptDao()
    }
    
    @Provides
    @Singleton
    fun provideAppParserRegistry(): AppParserRegistry {
        val appParsers = listOf(
            RevolutParser(),
            GoogleWalletParser(),
            GreekBankParser(),
            SmsParser()
        )
        val fallbackParser = GenericTransactionParser()
        return AppParserRegistry(appParsers, fallbackParser)
    }


    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideMerchantCategoryDao(database: AppDatabase): MerchantCategoryDao = database.merchantCategoryDao()

    @Provides
    @Singleton
    fun providePendingReviewDao(database: AppDatabase): PendingReviewDao = database.pendingReviewDao()

    @Provides
    @Singleton
    fun provideUserCorrectionDao(database: AppDatabase): UserCorrectionDao = database.userCorrectionDao()

    @Provides
    @Singleton
    fun provideSourceStatsDao(database: AppDatabase): SourceStatsDao = database.sourceStatsDao()

    @Provides
    @Singleton
    fun provideRecurringExpenseDao(database: AppDatabase): RecurringExpenseDao = database.recurringExpenseDao()
}
