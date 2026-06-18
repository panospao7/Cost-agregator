package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkPromoter
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkPromoterImpl
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkService
import com.yourname.expensetracker.domain.provenance.PendingReviewSourceLinkServiceImpl
import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.SourceLinkWriterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvenanceModule {
    @Binds
    abstract fun bindSourceLinkWriter(impl: SourceLinkWriterImpl): SourceLinkWriter

    @Binds @Singleton
    abstract fun bindPendingReviewSourceLinkService(impl: PendingReviewSourceLinkServiceImpl): PendingReviewSourceLinkService

    @Binds @Singleton
    abstract fun bindPendingReviewSourceLinkPromoter(impl: PendingReviewSourceLinkPromoterImpl): PendingReviewSourceLinkPromoter
}
