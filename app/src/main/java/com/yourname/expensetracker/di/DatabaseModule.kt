package com.yourname.expensetracker.di

import android.content.Context
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.GroupTransactionCoordinator
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as GroupTransactionCoordinatorInterface
import com.yourname.expensetracker.domain.util.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return AppDatabase.fileBuilder(context).build()
    }
    
    /**
     * HIGH-06 FIX: Provides atomic transaction coordinator.
     * Ensures multi-DAO operations are ACID compliant.
     * Returns the interface type, implemented by data layer class.
     */
    @Provides
    @Singleton
    fun provideGroupTransactionCoordinator(
        database: AppDatabase,
        groupDao: ExpenseGroupDao,
        memberDao: GroupMemberDao,
        groupExpenseDao: GroupExpenseDao,
        expenseDao: ExpenseDao,
        timeProvider: TimeProvider,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): GroupTransactionCoordinatorInterface {
        return GroupTransactionCoordinator(
            database = database,
            groupDao = groupDao,
            memberDao = memberDao,
            groupExpenseDao = groupExpenseDao,
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            ioDispatcher = ioDispatcher
        )
    }
}
