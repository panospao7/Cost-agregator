package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.AiArtifactDao
import com.yourname.expensetracker.data.database.dao.AiChatMessageDao
import com.yourname.expensetracker.data.database.dao.AiChatSessionDao
import com.yourname.expensetracker.data.ai.provider.HybridDashboardBriefingService
import com.yourname.expensetracker.data.ai.provider.HybridQueryInterpretationService
import com.yourname.expensetracker.data.ai.provider.CloudReviewExplanationService
import com.yourname.expensetracker.data.ai.provider.CloudReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.CloudCategorizationAssistService
import com.yourname.expensetracker.data.ai.provider.CloudDedupeJudgeService
import com.yourname.expensetracker.data.ai.provider.DefaultAiEnvironmentMonitor
import com.yourname.expensetracker.data.ai.provider.HybridCategorizationAssistService
import com.yourname.expensetracker.data.ai.provider.HybridDedupeJudgeService
import com.yourname.expensetracker.data.ai.provider.HybridReceiptAssistService
import com.yourname.expensetracker.data.ai.provider.HybridReviewExplanationService
import com.yourname.expensetracker.data.ai.provider.NoOpCategorizationAssistService
import com.yourname.expensetracker.data.ai.provider.NoOpDedupeJudgeService
import com.yourname.expensetracker.data.ai.provider.NoOpReceiptAssistService
import com.yourname.expensetracker.data.ai.worker.AiWorkSchedulerImpl
import com.yourname.expensetracker.data.repository.AiChatRepositoryImpl
import com.yourname.expensetracker.data.repository.AiArtifactRepositoryImpl
import com.yourname.expensetracker.data.repository.AiSettingsRepositoryImpl
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiChatRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.policy.AiPolicyImpl
import com.yourname.expensetracker.domain.ai.policy.DefaultAiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiEnvironmentMonitor
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.ai.service.AiWorkScheduler
import com.yourname.expensetracker.domain.ai.service.CategorizationAssistService
import com.yourname.expensetracker.domain.ai.service.DedupeJudgeService
import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService
import com.yourname.expensetracker.domain.ai.service.QueryInterpretationService
import com.yourname.expensetracker.domain.ai.service.ReceiptAssistService
import com.yourname.expensetracker.domain.ai.service.ReviewExplanationService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    // -------------------------------------------------------------------------
    // Interface → implementation bindings
    // -------------------------------------------------------------------------

    @Binds
    @Singleton
    abstract fun bindAiSettingsRepository(
        impl: AiSettingsRepositoryImpl
    ): AiSettingsRepository

    @Binds
    @Singleton
    abstract fun bindAiArtifactRepository(
        impl: AiArtifactRepositoryImpl
    ): AiArtifactRepository

    @Binds
    @Singleton
    abstract fun bindAiChatRepository(
        impl: AiChatRepositoryImpl
    ): AiChatRepository

    @Binds
    @Singleton
    abstract fun bindAiPolicy(
        impl: AiPolicyImpl
    ): AiPolicy

    @Binds
    @Singleton
    abstract fun bindAiCapabilityRouter(
        impl: DefaultAiCapabilityRouter
    ): AiCapabilityRouter

    @Binds
    @Singleton
    abstract fun bindAiEnvironmentMonitor(
        impl: DefaultAiEnvironmentMonitor
    ): AiEnvironmentMonitor

    @Binds
    @Singleton
    abstract fun bindAiWorkScheduler(
        impl: AiWorkSchedulerImpl
    ): AiWorkScheduler

    @Binds
    @Singleton
    abstract fun bindDashboardBriefingService(
        impl: HybridDashboardBriefingService
    ): DashboardBriefingService

    @Binds
    @Singleton
    abstract fun bindReviewExplanationService(
        impl: HybridReviewExplanationService
    ): ReviewExplanationService

    @Binds
    @Singleton
    abstract fun bindReceiptAssistService(
        impl: HybridReceiptAssistService
    ): ReceiptAssistService

    @Binds
    @Singleton
    abstract fun bindCategorizationAssistService(
        impl: HybridCategorizationAssistService
    ): CategorizationAssistService

    @Binds
    @Singleton
    abstract fun bindDedupeJudgeService(
        impl: HybridDedupeJudgeService
    ): DedupeJudgeService

    @Binds
    @Singleton
    abstract fun bindQueryInterpretationService(
        impl: HybridQueryInterpretationService
    ): QueryInterpretationService

    // -------------------------------------------------------------------------
    // DAO provision (companion object)
    // -------------------------------------------------------------------------

    companion object {
        @Provides
        @Singleton
        fun provideAiArtifactDao(database: AppDatabase): AiArtifactDao =
            database.aiArtifactDao()

        @Provides
        @Singleton
        fun provideAiChatSessionDao(database: AppDatabase): AiChatSessionDao =
            database.aiChatSessionDao()

        @Provides
        @Singleton
        fun provideAiChatMessageDao(database: AppDatabase): AiChatMessageDao =
            database.aiChatMessageDao()
    }
}
