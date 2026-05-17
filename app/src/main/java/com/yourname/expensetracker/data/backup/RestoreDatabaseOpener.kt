package com.yourname.expensetracker.data.backup

import android.content.Context
import com.yourname.expensetracker.data.database.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a fresh one-shot [AppDatabase] instance after a DB file swap.
 * The injected singleton [AppDatabase] must NOT be used after the live DB file
 * is replaced — it holds stale file handles and cached DAO references.
 */
interface RestoreDatabaseOpener {
    /** Opens a new Room instance pointing at the live DB file. Caller must close it. */
    fun openFreshDatabase(): AppDatabase
}

@Singleton
class RestoreDatabaseOpenerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : RestoreDatabaseOpener {
    override fun openFreshDatabase(): AppDatabase =
        AppDatabase.fileBuilder(context, AppDatabase.DATABASE_NAME).build()
}
