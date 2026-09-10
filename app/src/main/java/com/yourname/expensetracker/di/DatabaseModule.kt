package com.yourname.expensetracker.di

import android.content.Context
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.data.database.GroupTransactionCoordinator
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as GroupTransactionCoordinatorInterface
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleEventWriter
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
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
        transactionLifecycleCoordinator: TransactionLifecycleCoordinator,
        transactionLifecycleEventWriter: TransactionLifecycleEventWriter,
        transactionSideEffectPlanner: TransactionSideEffectPlanner,
        postCommitActionRunner: PostCommitActionRunner,
        writeBarrier: DatabaseWriteBarrier,
        timeProvider: TimeProvider,
        transactionRunner: DomainTransactionRunner,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ): GroupTransactionCoordinatorInterface {
        return GroupTransactionCoordinator(
            database = database,
            groupDao = groupDao,
            memberDao = memberDao,
            groupExpenseDao = groupExpenseDao,
            expenseDao = expenseDao,
            transactionLifecycleCoordinator = transactionLifecycleCoordinator,
            transactionLifecycleEventWriter = transactionLifecycleEventWriter,
            transactionSideEffectPlanner = transactionSideEffectPlanner,
            postCommitActionRunner = postCommitActionRunner,
            writeBarrier = writeBarrier,
            timeProvider = timeProvider,
            ioDispatcher = ioDispatcher,
            transactionRunner = transactionRunner
        )
    }

    /**
     * PR 3: Provides shared transaction runner wrapping Room's [androidx.room.withTransaction].
     * All domain coordinators that need atomic state+event writes should inject this.
     */
    @Provides
    @Singleton
    fun provideDomainTransactionRunner(
        impl: RoomDomainTransactionRunner
    ): DomainTransactionRunner = impl
}
