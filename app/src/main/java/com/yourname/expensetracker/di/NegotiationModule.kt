package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.negotiation.StaticMarketRateProvider
import com.yourname.expensetracker.domain.negotiation.MarketRateProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NegotiationModule {

    @Binds
    @Singleton
    abstract fun bindMarketRateProvider(
        impl: StaticMarketRateProvider
    ): MarketRateProvider
}
