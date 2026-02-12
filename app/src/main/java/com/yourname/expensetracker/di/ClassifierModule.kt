package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.intelligence.ITransactionClassifier
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ClassifierModule {

    @Binds
    @Singleton
    abstract fun bindTransactionClassifier(
        transactionClassifier: TransactionClassifier
    ): ITransactionClassifier
}
