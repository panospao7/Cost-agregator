package com.yourname.expensetracker.di

import com.yourname.expensetracker.data.repository.ExpenseMerchantClusterPortAdapter
import com.yourname.expensetracker.data.repository.MerchantLocationCachePortAdapter
import com.yourname.expensetracker.domain.location.LocationCachePort
import com.yourname.expensetracker.domain.location.MerchantClusterPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationResolverPortsModule {

    @Binds
    @Singleton
    abstract fun bindLocationCachePort(
        adapter: MerchantLocationCachePortAdapter
    ): LocationCachePort

    @Binds
    @Singleton
    abstract fun bindMerchantClusterPort(
        adapter: ExpenseMerchantClusterPortAdapter
    ): MerchantClusterPort
}
