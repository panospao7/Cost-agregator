package com.yourname.expensetracker.data.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.backup.RestoreMaintenanceModePeek

/**
 * R25/R27: the open helper produced by [GatedOpenHelperFactory]. A (re)open of the live database is
 * refused with [DatabaseOpenBlockedException] when the [DatabaseOpenGate] blocked the file, or while
 * a restore swap is in progress. Kept in its own file so the db_access structural-exception tuple
 * (class == file stem) stays exact.
 */
internal class GatedOpenHelper(
    private val context: Context,
    private val delegateHelper: SupportSQLiteOpenHelper
) : SupportSQLiteOpenHelper {

    @Volatile
    private var cachedDatabase: SupportSQLiteDatabase? = null

    override val databaseName: String?
        get() = delegateHelper.databaseName

    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        delegateHelper.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase
        get() = openGated(writable = true)

    override val readableDatabase: SupportSQLiteDatabase
        get() = openGated(writable = false)

    override fun close() {
        delegateHelper.close()
    }

    private fun openGated(writable: Boolean): SupportSQLiteDatabase {
        // Fast path: an already-open handle was admitted by an earlier gated open.
        // A cached read-only handle must not satisfy a writable request.
        cachedDatabase?.let { if (it.isOpen && (!writable || !it.isReadOnly)) return it }

        DatabaseOpenGate.blockedReasonOrNull(context)?.let { code ->
            throw DatabaseOpenBlockedException(code)
        }
        if (RestoreMaintenanceMode.isSwapLatched() || RestoreMaintenanceModePeek.isSwapping(context)) {
            throw DatabaseOpenBlockedException(GatedOpenHelperFactory.REASON_OPEN_REFUSED_DURING_SWAP)
        }

        val opened = if (writable) delegateHelper.writableDatabase else delegateHelper.readableDatabase
        cachedDatabase = opened
        return opened
    }
}
