package com.yourname.expensetracker.di

import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import timber.log.Timber

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return try {
            Room.databaseBuilder(
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
                AppDatabase.MIGRATION_23_24
            )
                .addCallback(object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onOpen(db)
                        Timber.d("Database opened successfully. Version: ${db.version}")
                    }
                })
                .addMigrations(
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
                    AppDatabase.MIGRATION_24_25
                )
                .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Failed to build database, falling back to destructive migration")
            Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                "expense_tracker_db"
            )
                .fallbackToDestructiveMigration()
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }
    }
}
