package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.NaturalLanguageExpenseQueryRepositoryImpl
import com.yourname.expensetracker.domain.naturallanguage.NaturalLanguageExpenseQueryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NaturalLanguageModule {

    @Binds
    @Singleton
    abstract fun bindNaturalLanguageExpenseQueryRepository(
        impl: NaturalLanguageExpenseQueryRepositoryImpl
    ): NaturalLanguageExpenseQueryRepository
}
