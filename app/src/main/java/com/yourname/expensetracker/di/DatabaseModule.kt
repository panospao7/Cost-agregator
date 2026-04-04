package com.yourname.expensetracker.di

import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.GroupTransactionCoordinator
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupExpenseDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import com.yourname.expensetracker.domain.groups.GroupTransactionCoordinator as GroupTransactionCoordinatorInterface
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
        ).addMigrations(
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11,
            AppDatabase.MIGRATION_11_12,
            AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14,
            AppDatabase.MIGRATION_14_15,
            AppDatabase.MIGRATION_15_16,
            AppDatabase.MIGRATION_16_17,
            AppDatabase.MIGRATION_17_18,
            AppDatabase.MIGRATION_18_19,
            AppDatabase.MIGRATION_19_20,
            AppDatabase.MIGRATION_20_21,
            AppDatabase.MIGRATION_21_22,
            AppDatabase.MIGRATION_22_23,
            AppDatabase.MIGRATION_23_24,
            AppDatabase.MIGRATION_24_25,
            AppDatabase.MIGRATION_25_26,
            AppDatabase.MIGRATION_26_27,
            AppDatabase.MIGRATION_27_28,
            AppDatabase.MIGRATION_28_29,
            AppDatabase.MIGRATION_29_30,
            AppDatabase.MIGRATION_30_31,
            AppDatabase.MIGRATION_31_32,
            AppDatabase.MIGRATION_32_33,
            AppDatabase.MIGRATION_33_34,
            AppDatabase.MIGRATION_34_35,
            AppDatabase.MIGRATION_35_36,
            AppDatabase.MIGRATION_36_37,
            AppDatabase.MIGRATION_37_38,
            AppDatabase.MIGRATION_38_39,
            AppDatabase.MIGRATION_39_40,
            AppDatabase.MIGRATION_40_41,
            AppDatabase.MIGRATION_41_42,
            AppDatabase.MIGRATION_42_43,
            AppDatabase.MIGRATION_43_44,
            AppDatabase.MIGRATION_44_45,
            AppDatabase.MIGRATION_45_46,
            AppDatabase.MIGRATION_46_47,
            AppDatabase.MIGRATION_47_48,
            AppDatabase.MIGRATION_48_49,
            AppDatabase.MIGRATION_49_50,
            AppDatabase.MIGRATION_50_51,
            AppDatabase.MIGRATION_51_52,
            AppDatabase.MIGRATION_52_53,
            AppDatabase.MIGRATION_53_54,
            AppDatabase.MIGRATION_54_55,
            AppDatabase.MIGRATION_55_56,
            AppDatabase.MIGRATION_56_57,
            AppDatabase.MIGRATION_57_58,
            AppDatabase.MIGRATION_58_59,
            AppDatabase.MIGRATION_59_60,
            AppDatabase.MIGRATION_60_61,
            AppDatabase.MIGRATION_61_62,
            AppDatabase.MIGRATION_62_63,
            AppDatabase.MIGRATION_63_64,
            AppDatabase.MIGRATION_64_65,
            AppDatabase.MIGRATION_65_66,
            AppDatabase.MIGRATION_66_67,
            AppDatabase.MIGRATION_67_68
        )
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
        groupExpenseDao: GroupExpenseDao
    ): GroupTransactionCoordinatorInterface {
        return GroupTransactionCoordinator(database, groupDao, memberDao, groupExpenseDao)
    }
}
