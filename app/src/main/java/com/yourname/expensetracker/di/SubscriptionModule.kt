package com.yourname.expensetracker.di

import com.yourname.expensetracker.domain.subscription.SubscriptionManagerEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SubscriptionModule {
    
    @Provides
    @Singleton
    fun provideSubscriptionManagerEngine(engine: SubscriptionManagerEngine): SubscriptionManagerEngine {
        return engine
    }
}
