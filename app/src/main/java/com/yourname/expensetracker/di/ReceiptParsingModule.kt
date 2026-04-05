package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.receipt.MerchantRulesPolicy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReceiptParsingModule {

    @Binds
    @Singleton
    abstract fun bindMerchantRulesPolicy(
        impl: MerchantRulesRepository
    ): MerchantRulesPolicy
}
