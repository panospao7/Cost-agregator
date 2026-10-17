package com.yourname.expensetracker.data.database

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * R25 D-M2: startup decision for a blocked [DatabaseOpenGate].
 *
 * A crash during a restore swap can leave the live file unreadable (DB_OPEN_FAILED) while the
 * restore journal still holds the safety backup that repairs it. In exactly that case the
 * journal-recovery stage (raw SQLite and the unwrapped restore opener, never the gated Room helper)
 * must run before the gate verdict is final. Every other blocked reason, or an OPEN_FAILED without
 * an active journal, stays blocked without any recovery attempt. Recovery failure is fail-closed.
 */
internal object BlockedGateStartupRecovery {

    private const val LOG_TAG = "BlockedGateRecovery"

    /**
     * @return the final blocking reason code, or null when the open may proceed.
     */
    fun resolve(
        initialReason: String?,
        isJournalActive: () -> Boolean,
        runJournalRecovery: () -> Unit,
        reevaluate: () -> String?
    ): String? {
        if (initialReason != DatabaseOpenGate.REASON_OPEN_FAILED) return initialReason
        if (!isJournalActive()) return initialReason
        try {
            runJournalRecovery()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "journal recovery failed class=" + e.javaClass.simpleName)
            return initialReason
        }
        return reevaluate()
    }
}
