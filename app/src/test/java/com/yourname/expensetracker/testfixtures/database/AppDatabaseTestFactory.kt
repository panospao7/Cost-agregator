package com.yourname.expensetracker.testfixtures.database

import android.content.Context
import com.yourname.expensetracker.data.database.AppDatabase

/**
 * Factory for creating in-memory [AppDatabase] instances for testing.
 *
 * Uses [AppDatabase.inMemoryBuilder] which applies all migrations and the
 * [AppDatabase.Companion.FRESH_INSTALL_CALLBACK] but runs in-memory (no file I/O).
 * Main-thread queries are allowed by the builder so tests don't need
 * [kotlinx.coroutines.Dispatchers.Main] setup for DAO access.
 */
object AppDatabaseTestFactory {
    fun create(context: Context): AppDatabase {
        return AppDatabase.inMemoryBuilder(context).build()
    }
}
