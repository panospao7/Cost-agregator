package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Thrown when the gated open helper refuses to open the live database. The message is a bounded
 * reason code only. Deliberately NOT a SQLiteException so no corruption/recovery handler can mistake
 * it for a damaged file.
 */
class DatabaseOpenBlockedException(val reasonCode: String) : IllegalStateException(reasonCode)

/**
 * R25/R27: wraps the Room open helper (see [GatedOpenHelper]) so a (re)open of the live database is
 * refused when the [DatabaseOpenGate] blocked the file, or while a restore swap is in progress.
 *
 * Only the live app database built in `DatabaseModule` uses this factory; staged/fresh instances are
 * built through the unwrapped `AppDatabase.fileBuilder`.
 */
class GatedOpenHelperFactory(
    context: Context,
    private val delegate: SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper.Factory {

    private val appContext: Context = context.applicationContext ?: context

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        GatedOpenHelper(appContext, delegate.create(configuration))

    companion object {
        const val REASON_OPEN_REFUSED_DURING_SWAP = "DB_OPEN_REFUSED_DURING_SWAP"
    }
}
