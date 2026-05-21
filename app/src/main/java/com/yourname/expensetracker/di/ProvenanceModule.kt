package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.provenance.SourceLinkWriter
import com.yourname.expensetracker.domain.provenance.SourceLinkWriterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ProvenanceModule {
    @Binds
    abstract fun bindSourceLinkWriter(impl: SourceLinkWriterImpl): SourceLinkWriter
}
