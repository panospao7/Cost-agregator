package com.yourname.expensetracker.di

import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.GroupTransactionCoordinator
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as GroupTransactionCoordinatorInterface
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
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "expense_tracker_db"
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(AppDatabase.FRESH_INSTALL_CALLBACK)
            // ISSUE-1: Never destructively wipe user data on migration failures.
            // Old schemas must be migrated explicitly or handled through backup/recovery UX.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
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
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): GroupTransactionCoordinatorInterface {
        return GroupTransactionCoordinator(database, groupDao, memberDao, groupExpenseDao, expenseDao, ioDispatcher)
    }
}
