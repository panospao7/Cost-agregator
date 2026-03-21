package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {

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

    @Provides
    @Singleton
    fun provideMerchantNormalizationDao(database: AppDatabase): MerchantNormalizationDao = database.merchantNormalizationDao()

    @Provides
    @Singleton
    fun provideMerchantLocationDao(database: AppDatabase): MerchantLocationDao = database.merchantLocationDao()

    @Provides
    @Singleton
    fun provideRecommendationDao(database: AppDatabase): RecommendationDao = database.recommendationDao()
}
