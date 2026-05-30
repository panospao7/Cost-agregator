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
    fun provideNotificationIntakeDao(database: AppDatabase): NotificationIntakeDao {
        return database.notificationIntakeDao()
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

    @Deprecated("Use ManualRecurringExpenseDao instead. Kept for backward compatibility during migration.")
    @Provides
    @Singleton
    fun provideRecurringExpenseDao(database: AppDatabase): RecurringExpenseDao = database.recurringExpenseDao()

    @Provides
    @Singleton
    fun provideManualRecurringExpenseDao(database: AppDatabase): ManualRecurringExpenseDao = 
        database.manualRecurringExpenseDao()

    @Provides
    @Singleton
    fun provideMerchantNormalizationDao(database: AppDatabase): MerchantNormalizationDao = database.merchantNormalizationDao()

    @Provides
    @Singleton
    fun provideMerchantLocationDao(database: AppDatabase): MerchantLocationDao = database.merchantLocationDao()

    @Provides
    @Singleton
    fun provideRecommendationDao(database: AppDatabase): RecommendationDao = database.recommendationDao()

    @Provides
    @Singleton
    fun provideReceiptItemCategorizationDao(database: AppDatabase): ReceiptItemCategorizationDao = 
        database.receiptItemCategorizationDao()

    @Provides
    @Singleton
    fun provideWarrantyDao(database: AppDatabase): WarrantyDao = database.warrantyDao()

    @Provides
    @Singleton
    fun provideReturnWindowDao(database: AppDatabase): ReturnWindowDao = database.returnWindowDao()

    @Provides
    @Singleton
    fun provideWarrantyLifecycleEventDao(database: AppDatabase): WarrantyLifecycleEventDao =
        database.warrantyLifecycleEventDao()

    @Provides
    @Singleton
    fun provideWarrantyReminderDeliveryDao(database: AppDatabase): WarrantyReminderDeliveryDao =
        database.warrantyReminderDeliveryDao()

    @Provides
    @Singleton
    fun provideSubscriptionPriceHistoryDao(database: AppDatabase): SubscriptionPriceHistoryDao = 
        database.subscriptionPriceHistoryDao()

    @Provides
    @Singleton
    fun provideSubscriptionUsageDao(database: AppDatabase): SubscriptionUsageDao = 
        database.subscriptionUsageDao()

    @Provides
    @Singleton
    fun provideMileageTrackingDao(database: AppDatabase): MileageTrackingDao = 
        database.mileageTrackingDao()

    @Provides
    @Singleton
    fun provideExchangeRateDao(database: AppDatabase): ExchangeRateDao = 
        database.exchangeRateDao()

    @Provides
    @Singleton
    fun provideExpenseGroupDao(database: AppDatabase): ExpenseGroupDao = 
        database.expenseGroupDao()

    @Provides
    @Singleton
    fun provideGroupMemberDao(database: AppDatabase): GroupMemberDao = 
        database.groupMemberDao()

    @Provides
    @Singleton
    fun provideGroupExpenseDao(database: AppDatabase): GroupExpenseDao = 
        database.groupExpenseDao()

    @Provides
    @Singleton
    fun provideGroupSettlementDao(database: AppDatabase): GroupSettlementDao =
        database.groupSettlementDao()

    @Provides
    @Singleton
    fun provideBudgetForecastDao(database: AppDatabase): BudgetForecastDao = 
        database.budgetForecastDao()

    @Provides
    @Singleton
    fun provideInvestmentDao(database: AppDatabase): InvestmentDao = 
        database.investmentDao()

    @Provides
    @Singleton
    fun provideInvestmentValueDao(database: AppDatabase): InvestmentValueDao = 
        database.investmentValueDao()

    @Provides
    @Singleton
    fun provideInvestmentTransactionDao(database: AppDatabase): InvestmentTransactionDao =
        database.investmentTransactionDao()

    @Provides
    @Singleton
    fun provideBankConnectionDao(database: AppDatabase): BankConnectionDao = 
        database.bankConnectionDao()

    @Provides
    @Singleton
    fun provideSplitTemplateDao(database: AppDatabase): SplitTemplateDao = 
        database.splitTemplateDao()

    @Provides
    @Singleton
    fun provideSplitItemAssignmentDao(database: AppDatabase): SplitItemAssignmentDao = 
        database.splitItemAssignmentDao()

    @Provides
    @Singleton
    fun provideSubscriptionCandidateDao(database: AppDatabase): com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao = 
        database.subscriptionCandidateDao()

    @Provides
    @Singleton
    fun provideBudgetAdjustmentDao(database: AppDatabase): BudgetAdjustmentDao = 
        database.budgetAdjustmentDao()

    @Provides
    @Singleton
    fun provideEmailReceiptDao(database: AppDatabase): EmailReceiptDao = 
        database.emailReceiptDao()

    @Provides
    @Singleton
    fun provideAnomalyAlertDao(database: AppDatabase): AnomalyAlertDao =
        database.anomalyAlertDao()

    @Provides
    @Singleton
    fun provideHealthScoreHistoryDao(database: AppDatabase): HealthScoreHistoryDao =
        database.healthScoreHistoryDao()

    @Provides
    @Singleton
    fun providePromptStateDao(database: AppDatabase): PromptStateDao =
        database.promptStateDao()

    @Provides
    @Singleton
    fun provideSpendingPersonalityProfileDao(database: AppDatabase): SpendingPersonalityProfileDao =
        database.spendingPersonalityProfileDao()

    @Provides
    @Singleton
    fun provideStressForecastSnapshotDao(database: AppDatabase): StressForecastSnapshotDao =
        database.stressForecastSnapshotDao()

    @Provides
    @Singleton
    fun provideSavingsSweepPlanDao(database: AppDatabase): SavingsSweepPlanDao =
        database.savingsSweepPlanDao()

    @Provides
    @Singleton
    fun provideSpendingChallengeDao(database: AppDatabase): SpendingChallengeDao =
        database.spendingChallengeDao()

    @Provides
    @Singleton
    fun provideTransactionEventDao(database: AppDatabase): TransactionEventDao =
        database.transactionEventDao()

    @Provides
    @Singleton
    fun provideReceiptEventDao(database: AppDatabase): ReceiptEventDao =
        database.receiptEventDao()

    @Provides
    @Singleton
    fun provideReceiptExpenseLinkDao(database: AppDatabase): ReceiptExpenseLinkDao =
        database.receiptExpenseLinkDao()

    @Provides
    @Singleton
    fun provideRecurringOccurrenceDao(database: AppDatabase): RecurringOccurrenceDao =
        database.recurringOccurrenceDao()

    @Provides
    @Singleton
    fun provideRecurringReminderDeliveryDao(database: AppDatabase): RecurringReminderDeliveryDao =
        database.recurringReminderDeliveryDao()

    @Provides
    @Singleton
    fun provideRecurringLifecycleEventDao(database: AppDatabase): RecurringLifecycleEventDao =
        database.recurringLifecycleEventDao()

    @Provides
    @Singleton
    fun providePrivacyAuditDao(database: AppDatabase): PrivacyAuditDao =
        database.privacyAuditDao()

    @Provides
    @Singleton
    fun provideGroupLifecycleEventDao(database: AppDatabase): GroupLifecycleEventDao =
        database.groupLifecycleEventDao()

    @Provides
    @Singleton
    fun provideBackgroundJobRunDao(database: AppDatabase): BackgroundJobRunDao =
        database.backgroundJobRunDao()

    @Provides
    @Singleton
    fun providePipelineDiagnosticEventDao(database: AppDatabase): PipelineDiagnosticEventDao =
        database.pipelineDiagnosticEventDao()

    @Provides
    @Singleton
    fun provideOperationRunDao(database: AppDatabase): OperationRunDao =
        database.operationRunDao()

    @Provides
    @Singleton
    fun provideOperationRunEventDao(database: AppDatabase): OperationRunEventDao =
        database.operationRunEventDao()

    @Provides
    @Singleton
    fun provideEntitySourceLinkDao(database: AppDatabase): EntitySourceLinkDao =
        database.entitySourceLinkDao()

    @Provides
    @Singleton
    fun provideBankStatementImportRunDao(database: AppDatabase): BankStatementImportRunDao =
        database.bankStatementImportRunDao()

    @Provides
    @Singleton
    fun provideBankStatementImportItemDao(database: AppDatabase): BankStatementImportItemDao =
        database.bankStatementImportItemDao()
}
